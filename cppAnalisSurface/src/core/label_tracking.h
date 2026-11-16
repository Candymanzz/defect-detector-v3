#pragma once
#include "reference.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>

namespace cas {
// Verify the predicted boundary; never fit away a missing piece of the label.
inline double labelBoundarySupport(const cv::Mat& reference, const cv::Mat& current,
                                   const NormPolygon& contour, cv::Point2d shift) {
    int usable=0, matched=0;
    auto sample=[](const cv::Mat& image, cv::Point2d q, cv::Vec3d& value) {
        const int x=cvRound(q.x), y=cvRound(q.y);
        if(x<0||y<0||x>=image.cols||y>=image.rows) return false;
        value=image.at<cv::Vec3b>(y,x); return true;
    };
    for(size_t i=0;i<contour.size();++i) {
        cv::Point2d a(contour[i].x*(reference.cols-1),contour[i].y*(reference.rows-1));
        const auto q=contour[(i+1)%contour.size()];
        cv::Point2d b(q.x*(reference.cols-1),q.y*(reference.rows-1));
        const double length=cv::norm(b-a); if(length<1) continue;
        const cv::Point2d normal(-(b.y-a.y)/length,(b.x-a.x)/length);
        for(double t=4;t<length;t+=6) {
            const auto p=a+(b-a)*(t/length);
            cv::Vec3d inside,outside;
            if(!sample(reference,p+normal*3,inside)||!sample(reference,p-normal*3,outside)) continue;
            const auto gradient=inside-outside; const double strength=cv::norm(gradient);
            if(strength<20) continue;
            bool visible=false,found=false;
            for(int offset=-2;offset<=2;++offset) {
                cv::Vec3d c,d;
                if(!sample(current,p+shift+normal*(offset+3),c)||!sample(current,p+shift+normal*(offset-3),d)) continue;
                visible=true; const auto actual=c-d;
                if(actual.dot(gradient)>strength*strength*.25) found=true;
            }
            if(visible) { ++usable; if(found) ++matched; }
        }
    }
    return usable>=16 ? double(matched)/usable : 0.;
}

struct LabelBoundaryRefinement {
    cv::Mat nativeMask; // empty = retain the confirmed reference contour
    std::string status = "insufficient_edges";
    int candidates = 0, inliers = 0;
    double maxInset = 0;
};

namespace label_boundary_detail {
constexpr double kSearchRadius = 5.; // native pixels, never a fixed erosion width
struct Point {
    cv::Point2d position, normal, tangent;
    double arc = 0, offset = 0;
    bool searchable = true, matched = false, inlier = false;
};
inline bool sample(const cv::Mat& image, cv::Point2d p, cv::Vec3d& color) {
    if(p.x<0 || p.y<0 || p.x>=image.cols-1 || p.y>=image.rows-1) return false;
    const int x=int(p.x), y=int(p.y);const double dx=p.x-x, dy=p.y-y;
    color=cv::Vec3d(image.at<cv::Vec3b>(y,x))*((1-dx)*(1-dy))+
          cv::Vec3d(image.at<cv::Vec3b>(y,x+1))*(dx*(1-dy))+
          cv::Vec3d(image.at<cv::Vec3b>(y+1,x))*((1-dx)*dy)+
          cv::Vec3d(image.at<cv::Vec3b>(y+1,x+1))*(dx*dy);
    return true;
}
inline bool averaged(const cv::Mat& image, cv::Point2d p, cv::Point2d tangent, cv::Vec3d& color) {
    color={0,0,0};
    for(double t:{-1.5,0.,1.5}) { cv::Vec3d c;if(!sample(image,p+tangent*t,c)) return false;color+=c; }
    color/=3.;return true;
}
// Match the direction and levels of the reference edge, not a generic white pixel.
inline bool crossing(const cv::Mat& image, cv::Point2d p, cv::Point2d n, cv::Point2d tangent,
                     cv::Vec3d outside, cv::Vec3d gradient, double lo, double hi,
                     double expected, double& result) {
    const double strength=gradient.dot(gradient);double previous=0, best=1e9, second=1e9;
    bool valid=false, found=false;
    for(double d=lo;d<=hi+1e-6;d+=.5) {
        cv::Vec3d c;
        if(!averaged(image,p+n*d,tangent,c)) { valid=false;continue; }
        const double value=(c-outside).dot(gradient)/strength;
        if(valid && previous<.5 && value>=.5) {
            const double at=d-.5+.5*(.5-previous)/std::max(1e-9,value-previous);
            cv::Vec3d a,b;
            if(averaged(image,p+n*(at-2),tangent,a) && averaged(image,p+n*(at+2),tangent,b)) {
                const auto g=b-a;const double norm=cv::norm(g);
                const double projection=g.dot(gradient)/strength;
                if(projection>=.45 && norm>1 && g.dot(gradient)/(norm*std::sqrt(strength))>=.65) {
                    const double cost=std::abs(at-expected);
                    if(cost<best) { second=best;best=cost;result=at;found=true; }
                    else second=std::min(second,cost);
                }
            }
        }
        previous=value;valid=true;
    }
    return found && second-best>=.5;
}
inline std::array<double,5> basis(double arc,double perimeter) {
    const double a=2*CV_PI*arc/perimeter;
    return {1.,std::cos(a),std::sin(a),std::cos(2*a),std::sin(2*a)};
}
inline double evaluate(const cv::Mat& coefficients,double arc,double perimeter) {
    const auto b=basis(arc,perimeter);double result=0;
    for(int i=0;i<5;++i) result+=b[i]*coefficients.at<double>(i);
    return result;
}
}

// Fit one low-frequency closed curve. Local notches must remain outliers, not
// become the accepted edge. Never mutate the reference or any cached mask.
inline LabelBoundaryRefinement refineLabelBoundary(const cv::Mat& reference,const cv::Mat& current,
        const NormPolygon& contour,cv::Point2d shift,const cv::Mat& excluded={}) {
    using namespace label_boundary_detail;
    LabelBoundaryRefinement out;
    if(contour.size()<3 || reference.type()!=CV_8UC3 || current.type()!=CV_8UC3) return out;
    std::vector<cv::Point2d> vertices,normals;
    double area=0,perimeter=0;
    for(const auto& q:contour) vertices.emplace_back(q.x*(reference.cols-1),q.y*(reference.rows-1));
    for(size_t i=0;i<vertices.size();++i) {
        const auto a=vertices[i],b=vertices[(i+1)%vertices.size()];
        area+=a.x*b.y-b.x*a.y;perimeter+=cv::norm(b-a);
    }
    if(perimeter<80 || std::abs(area)<1) return out;
    const double spacing=std::max(5.,perimeter/320.);
    for(size_t i=0;i<vertices.size();++i) {
        const auto delta=vertices[(i+1)%vertices.size()]-vertices[i];
        const double length=cv::norm(delta);
        normals.emplace_back(length>1e-6 ? cv::Point2d(-delta.y,delta.x)*(area>0 ? 1./length : -1./length) : cv::Point2d{});
    }
    std::vector<Point> points;double arc=0;int eligible=0;
    for(size_t i=0;i<vertices.size();++i) {
        const auto a=vertices[i],b=vertices[(i+1)%vertices.size()];
        const double length=cv::norm(b-a);if(length<1e-6) continue;
        const auto tangent=(b-a)/length,n=normals[i],previous=normals[(i+normals.size()-1)%normals.size()];
        auto miter=(previous+n)/std::max(.5,1.+previous.dot(n));
        if(cv::norm(miter)>2) miter*=2/cv::norm(miter);
        points.push_back({a,miter,tangent,arc,0,false});
        for(double t=spacing/2;t<length-spacing/2;t+=spacing) {
            Point point{a+tangent*t,n,tangent,arc+t};
            const auto p=point.position;
            if(p.x<0 || p.y<0 || p.x>=reference.cols || p.y>=reference.rows) { points.push_back(point);continue; }
            if(!excluded.empty() && excluded.at<unsigned char>(cvRound(p.y),cvRound(p.x))) { points.push_back(point);continue; }
            cv::Vec3d inside,outside;
            if(!averaged(reference,p+n*4,tangent,inside) || !averaged(reference,p-n*4,tangent,outside)) { points.push_back(point);continue; }
            const auto gradient=inside-outside;
            if(cv::norm(gradient)<30) { points.push_back(point);continue; }
            double refEdge=0,curEdge=0;
            if(!crossing(reference,p,n,tangent,outside,gradient,-2.5,2.5,0,refEdge)) { points.push_back(point);continue; }
            ++eligible;
            if(crossing(current,p+shift,n,tangent,outside,gradient,refEdge-kSearchRadius,refEdge+kSearchRadius,refEdge,curEdge)
                && std::abs(curEdge-refEdge)<kSearchRadius-.5) {
                point.offset=curEdge-refEdge;point.matched=true;++out.candidates;
            }
            points.push_back(point);
        }
        arc+=length;
    }
    if(out.candidates<24 || out.candidates<.6*eligible) return out;
    cv::Mat design(out.candidates,5,CV_64F),values(out.candidates,1,CV_64F),weights(out.candidates,1,CV_64F,cv::Scalar(1)),coefficients;
    int row=0;
    for(const auto& point:points) if(point.matched) {
        const auto b=basis(point.arc,perimeter);
        for(int c=0;c<5;++c) design.at<double>(row,c)=b[c];
        values.at<double>(row++)=point.offset;
    }
    for(int iteration=0;iteration<7;++iteration) {
        auto a=design.clone(),b=values.clone();int usable=0;
        for(int r=0;r<a.rows;++r) {
            const double w=std::sqrt(weights.at<double>(r));a.row(r)*=w;b.at<double>(r)*=w;usable+=w>.01;
        }
        if(usable<16 || !cv::solve(a,b,coefficients,cv::DECOMP_SVD)) { out.status="inconsistent_edges";return out; }
        for(int r=0;r<design.rows;++r) {
            double predicted=0;for(int c=0;c<5;++c) predicted+=design.at<double>(r,c)*coefficients.at<double>(c);
            const double residual=std::abs(predicted-values.at<double>(r));
            weights.at<double>(r)=residual<=.6 ? 1. : (residual<1.5 ? .6/residual : 0.);
        }
    }
    for(auto& point:points) if(point.matched) {
        point.inlier=std::abs(point.offset-evaluate(coefficients,point.arc,perimeter))<=.85;
        out.inliers+=point.inlier;
    }
    if(out.inliers<24 || out.inliers<.75*out.candidates) { out.status="inconsistent_edges";return out; }
    const double window=std::max(48.,std::min(100.,perimeter*.12));
    std::vector<cv::Point> fitted,original;
    for(const auto& point:points) {
        int total=0,supported=0;
        for(const auto& other:points) if(other.searchable) {
            double d=std::abs(other.arc-point.arc);d=std::min(d,perimeter-d);
            if(d<=window/2) { ++total;supported+=other.inlier; }
        }
        const double support=total ? double(supported)/total : 0;
        const double blend=std::clamp((support-.4)/.3,0.,1.);
        const double value=evaluate(coefficients,point.arc,perimeter);
        const double inset=std::abs(value)<=kSearchRadius ? std::max(0.,value)*blend : 0.;
        out.maxInset=std::max(out.maxInset,inset);
        const auto p=point.position+point.normal*inset;
        fitted.emplace_back(cvRound(p.x),cvRound(p.y));
    }
    if(out.maxInset<=.5) { out.status="unchanged";return out; }
    for(const auto& p:vertices) original.emplace_back(cvRound(p.x),cvRound(p.y));
    cv::Mat base=cv::Mat::zeros(reference.size(),CV_8U),mask=base.clone();
    cv::fillPoly(base,std::vector<std::vector<cv::Point>>{original},255);
    cv::fillPoly(mask,std::vector<std::vector<cv::Point>>{fitted},255);mask &= base;
    if(cv::countNonZero(mask)<.85*cv::countNonZero(base)) { out.status="excessive_crop";return out; }
    out.nativeMask=mask;out.status="refined";return out;
}
}
