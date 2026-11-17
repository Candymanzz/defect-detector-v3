#pragma once
#include "reference.h"
#include "preprocessing.h"
#include <opencv2/imgproc.hpp>
#include <array>
#include <cmath>

namespace cas {
struct BucketEdgePoint {
    cv::Point2d point, normal;
    double strength=0;
    int quarter=0;
};
struct BucketTrackingPrepared {
    NormPolygon outline;
    cv::Mat mask;
    std::vector<BucketEdgePoint> points;
};
struct BucketTranslation {
    bool trusted=false;
    cv::Point2d shift;
    double support=0, residual=0;
    int candidates=0, inliers=0;
};

namespace bucket_visibility_detail {
inline bool sample(const cv::Mat& image,cv::Point2d p,double& value) {
    if(p.x<1 || p.y<1 || p.x>=image.cols-2 || p.y>=image.rows-2) return false;
    const int x=int(p.x),y=int(p.y);const double dx=p.x-x,dy=p.y-y;
    value=image.at<float>(y,x)*(1-dx)*(1-dy)+image.at<float>(y,x+1)*dx*(1-dy)
        +image.at<float>(y+1,x)*(1-dx)*dy+image.at<float>(y+1,x+1)*dx*dy;
    return true;
}
inline double normalGradient(const cv::Mat& gx,const cv::Mat& gy,cv::Point2d p,cv::Point2d n) {
    double x,y;
    return sample(gx,p,x) && sample(gy,p,y) ? std::abs(x*n.x+y*n.y) : 0.;
}
inline double peak(const cv::Mat& gx,const cv::Mat& gy,cv::Point2d p,cv::Point2d n,double& strength,double radius=3.) {
    double best=0;strength=-1;
    for(double d=-radius;d<=radius+.001;d+=.25) {
        const double score=normalGradient(gx,gy,p+n*d,n);
        if(score>strength) { strength=score;best=d; }
    }
    return best;
}
}

// Reference geometry only: never follow a notch or shrink the contour to a defect.
inline std::shared_ptr<BucketTrackingPrepared> prepareBucketTracking(const cv::Mat& reference,const NormPolygon& outline) {
    using namespace bucket_visibility_detail;
    auto result=std::make_shared<BucketTrackingPrepared>();result->outline=outline;
    result->mask=cv::Mat::zeros(reference.size(),CV_8U);
    std::vector<cv::Point> polygon;std::vector<cv::Point2d> vertices;
    double perimeter=0,area=0;
    for(auto p:outline) { vertices.emplace_back(p.x*(reference.cols-1),p.y*(reference.rows-1));polygon.emplace_back(cvRound(vertices.back().x),cvRound(vertices.back().y)); }
    if(vertices.size()<3) return result;
    cv::fillPoly(result->mask,std::vector<std::vector<cv::Point>>{polygon},255);
    for(size_t i=0;i<vertices.size();++i) {
        auto a=vertices[i],b=vertices[(i+1)%vertices.size()];perimeter+=cv::norm(b-a);area+=a.x*b.y-b.x*a.y;
    }
    cv::Mat gray=prepareInspectionGray(reference,reference.size()),gx,gy;
    cv::Sobel(gray,gx,CV_32F,1,0,3);cv::Sobel(gray,gy,CV_32F,0,1,3);
    double arc=0;const double spacing=std::max(6.,perimeter/160.);
    for(size_t i=0;i<vertices.size();++i) {
        auto a=vertices[i],delta=vertices[(i+1)%vertices.size()]-a;const double length=cv::norm(delta);
        if(length<1) continue;
        const cv::Point2d tangent=delta/length,n(-tangent.y,tangent.x);
        for(double t=spacing/2;t<length;t+=spacing) {
            const auto p=a+tangent*t;
            // Image borders are camera clipping, not observable bucket edges.
            if(p.x<5 || p.y<5 || p.x>=reference.cols-5 || p.y>=reference.rows-5) continue;
            double strength=0;const double offset=peak(gx,gy,p,n,strength);
            if(strength<30 || std::abs(offset)>=2.9) continue;
            result->points.push_back({p+n*offset,n,strength,std::min(3,int(4*(arc+t)/perimeter))});
        }
        arc+=length;
    }
    return result;
}

// Fit one translation to physical silhouette evidence, independently of print alignment.
// Significant tilt, missing edges or inconsistent local evidence fail closed.
inline BucketTranslation estimateBucketTranslation(const BucketTrackingPrepared& reference,const cv::Mat& gray) {
    using namespace bucket_visibility_detail;
    BucketTranslation result;result.candidates=int(reference.points.size());
    if(reference.points.size()<32) return result;
    cv::Mat gx,gy;cv::Sobel(gray,gx,CV_32F,1,0,3);cv::Sobel(gray,gy,CV_32F,0,1,3);
    cv::Mat magnitude,localMaximum;
    cv::magnitude(gx,gy,magnitude);cv::dilate(magnitude,localMaximum,cv::getStructuringElement(cv::MORPH_RECT,{7,7}));
    double best=-1;cv::Point2d coarse;
    for(int y=-24;y<=24;y+=3) for(int x=-24;x<=24;x+=3) {
        double score=0;
        for(const auto& p:reference.points) {
            const auto at=p.point+cv::Point2d(x,y);double maximum=0;
            if(sample(localMaximum,at,maximum) && maximum>=std::max(18.,p.strength*.15))
                score+=std::min(1.,normalGradient(gx,gy,at,p.normal)/maximum);
        }
        score/=reference.points.size();
        if(score>best) { best=score;coarse={double(x),double(y)}; }
    }
    if(best<.35) return result;
    struct Observation { cv::Point2d normal;double distance;cv::Point2d point; };
    std::vector<Observation> observations;
    for(const auto& p:reference.points) {
        double strength=0;const double d=peak(gx,gy,p.point+coarse,p.normal,strength,5.);
        if(strength<std::max(18.,p.strength*.15) || std::abs(d)>=4.9) continue;
        observations.push_back({p.normal,p.normal.dot(coarse)+d,p.point});
    }
    if(observations.size()<32 || observations.size()<reference.points.size()*.6) return result;
    auto shift=coarse;
    for(int iteration=0;iteration<8;++iteration) {
        double xx=0,xy=0,yy=0,bx=0,by=0;
        for(const auto& p:observations) {
            const double residual=std::abs(p.distance-p.normal.dot(shift));
            const double weight=residual<=.75 ? 1. : .75*.75/(residual*residual);
            xx+=weight*p.normal.x*p.normal.x;xy+=weight*p.normal.x*p.normal.y;yy+=weight*p.normal.y*p.normal.y;
            bx+=weight*p.normal.x*p.distance;by+=weight*p.normal.y*p.distance;
        }
        const double determinant=xx*yy-xy*xy;
        if(determinant<(xx+yy)*(xx+yy)*.015) return result;
        shift={(yy*bx-xy*by)/determinant,(xx*by-xy*bx)/determinant};
    }
    double squared=0,xx=0,xy=0,yy=0;
    cv::Point2d allMin(1e9,1e9),allMax(-1e9,-1e9),inMin=allMin,inMax=allMax;
    for(const auto& p:reference.points) {
        allMin.x=std::min(allMin.x,p.point.x);allMin.y=std::min(allMin.y,p.point.y);
        allMax.x=std::max(allMax.x,p.point.x);allMax.y=std::max(allMax.y,p.point.y);
    }
    for(const auto& p:observations) {
        const double residual=p.distance-p.normal.dot(shift);
        if(std::abs(residual)>.75) continue;
        ++result.inliers;squared+=residual*residual;
        inMin.x=std::min(inMin.x,p.point.x);inMin.y=std::min(inMin.y,p.point.y);
        inMax.x=std::max(inMax.x,p.point.x);inMax.y=std::max(inMax.y,p.point.y);
        xx+=p.normal.x*p.normal.x;xy+=p.normal.x*p.normal.y;yy+=p.normal.y*p.normal.y;
    }
    if(result.inliers<32 || result.inliers<observations.size()*.7 || result.inliers<reference.points.size()*.55
            || inMax.x-inMin.x<(allMax.x-allMin.x)*.5 || inMax.y-inMin.y<(allMax.y-allMin.y)*.5
            || xx*yy-xy*xy<(xx+yy)*(xx+yy)*.015) return result;
    result.residual=std::sqrt(squared/result.inliers);
    if(result.residual>.45 || std::abs(shift.x)>22.5 || std::abs(shift.y)>22.5) return result;
    if(std::abs(shift.x)<1e-4) shift.x=0;if(std::abs(shift.y)<1e-4) shift.y=0;
    result.shift=shift;result.support=double(result.inliers)/reference.points.size();result.trusted=true;
    return result;
}
}
