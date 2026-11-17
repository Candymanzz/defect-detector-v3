#include "core/inspector.h"
#include <opencv2/imgproc.hpp>
#include <iostream>
#include <cmath>
#include <vector>
#include <stdexcept>

void require(bool ok,const char* text) { if(!ok) throw std::runtime_error(text); }
cas::NormPolygon normalize(const std::vector<cv::Point>& points,cv::Size size) {
    cas::NormPolygon result;for(auto p:points) result.emplace_back(p.x/double(size.width-1),p.y/double(size.height-1));return result;
}
cv::Mat fullInspection(const cas::InspectOutcome& out) {
    cv::Mat result=cv::Mat::zeros(out.workSize,CV_8U);
    if(!out.inspectionMask.empty()) out.inspectionMask.copyTo(result(out.region));
    return result;
}
int main() {
 try {
    cv::setNumThreads(1);
    const cv::Size size(512,384);
    std::vector<cv::Point> polygon;
    for(int i=0;i<48;++i) {
        const double a=2*CV_PI*i/48;polygon.emplace_back(cvRound(256+160*std::cos(a)),cvRound(192+120*std::sin(a)));
    }
    const auto contour=normalize(polygon,size);
    cv::Mat label=cv::Mat::zeros(size,CV_8U),inner;
    cv::fillPoly(label,std::vector<std::vector<cv::Point>>{polygon},255);
    cv::erode(label,inner,cv::getStructuringElement(cv::MORPH_ELLIPSE,{35,35}));
    cv::Mat reference(size,CV_8UC3,cv::Scalar(220,220,220));reference.setTo(cv::Scalar(40,70,110),label);
    cv::Mat texture(size,CV_8UC3);cv::RNG rng(17);rng.fill(texture,cv::RNG::UNIFORM,30,170);
    cv::putText(texture,"LABEL",{155,185},cv::FONT_HERSHEY_SIMPLEX,1.4,{0,0,0},5);
    cv::line(texture,{145,215},{365,215},{255,255,255},7);
    cv::rectangle(texture,{180,230,110,25},{0,0,0},cv::FILLED);
    texture.copyTo(reference,inner);
    cas::Reference ref(reference.clone());ref.setRoi(contour);
    cas::Settings settings;settings.labelOutline=contour;settings.horizontalShiftMm=30;settings.verticalShiftMm=30;
    settings.threshold=.1;settings.minBlobArea=20;
    const auto prepared=ref.prepared(1.,{},contour);
    const auto cachedMask=prepared->roiMask.clone();
    int cases=0;
    for(double scale:{1.,.5}) for(auto shift:{cv::Point(0,-4),cv::Point(0,4),cv::Point(-4,0),cv::Point(4,0)}) {
        auto s=settings;s.inspectScale=scale;
        cv::Mat frame,transform=(cv::Mat_<double>(2,3)<<1,0,shift.x,0,1,shift.y);
        cv::warpAffine(reference,frame,transform,size,cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(220,220,220));
        const auto normal=cas::inspectFrame(ref,frame,s,true);
        std::cout<<"scale="<<scale<<" shift="<<shift<<" measured="<<normal.shift<<" confidence="<<normal.shiftResponse<<" refined="<<normal.shiftRefined<<" score="<<normal.anomalyScore<<" reason="<<normal.reason<<'\n';
        require(!normal.reject && normal.anomalyScore==0,"normal shifted label must pass");
        auto base=cas::inspectFrame(ref,reference,s,true);
        require(cv::norm(fullInspection(normal),fullInspection(base),cv::NORM_INF)==0,
            "crossing the old current-frame ROI must not trim the reference inspection mask");
        cv::Mat allowed;
        allowed=ref.prepared(scale,{},contour)->roiMask;
        require(cv::countNonZero(fullInspection(normal) & ~allowed)==0,"sampling must never expand past the reference label/ROI");
        cv::Rect chip;cv::Point probe;
        if(shift.y<0) { chip={246,72,20,6};probe={256,75}; }
        else if(shift.y>0) { chip={246,306,20,7};probe={256,309}; }
        else if(shift.x<0) { chip={96,182,7,20};probe={99,192}; }
        else { chip={410,182,7,20};probe={413,192}; }
        cv::rectangle(frame,chip+shift,cv::Scalar(220,220,220),cv::FILLED);
        const auto defect=cas::inspectFrame(ref,frame,s,true);
        const auto mask=fullInspection(defect);
        require(mask.at<unsigned char>(cvRound(probe.y*scale),cvRound(probe.x*scale))!=0,
            "edge defect outside the old current ROI must stay inside the inspection mask");
        require(defect.anomalyScore>0 && defect.reject,"edge damage must not be hidden by the old ROI boundary");
        ++cases;
    }
    require(cv::norm(cachedMask,prepared->roiMask,cv::NORM_INF)==0,"reference cache must remain immutable");
    // Distracting pixels outside a narrower reference ROI must remain uninspected.
    auto narrow=normalize({{160,130},{350,130},{350,250},{160,250}},size);
    cas::Reference partial(reference.clone());partial.setRoi(narrow);
    cv::Mat distraction=reference.clone();cv::rectangle(distraction,{130,150,29,70},{0,0,0},cv::FILLED);
    const auto outside=cas::inspectFrame(partial,distraction,settings,true);
    require(!outside.reject && outside.anomalyScore==0,"full-frame access must not include pixels outside reference ROI");
    const auto outsideMask=fullInspection(outside);
    require(outsideMask.at<unsigned char>(180,150)==0,"outside reference ROI remains excluded");
    // A changed physical edge is inspected, never used to shrink the mask.
    cv::Mat contracted;
    cv::erode(label,contracted,cv::getStructuringElement(cv::MORPH_ELLIPSE,{7,7}));
    cv::Mat broadDamage=reference.clone();broadDamage.setTo(cv::Scalar(220,220,220),label & ~contracted);
    const auto broad=cas::inspectFrame(ref,broadDamage,settings,true);
    const auto unchanged=cas::inspectFrame(ref,reference,settings,true);
    require(cv::norm(fullInspection(broad),fullInspection(unchanged),cv::NORM_INF)==0,
        "a changed current boundary must never refine or shrink the confirmed contour");
    require(broad.anomalyScore>0 && broad.reject,"broad damage along the edge must stay visible");
    // A required part leaving the actual image must never silently become normal.
    cv::Mat nearFrame(size,CV_8UC3,cv::Scalar(220,220,220));
    cv::rectangle(nearFrame,{100,4,300,146},{40,70,110},cv::FILLED);
    texture(cv::Rect(120,25,260,100)).copyTo(nearFrame(cv::Rect(120,25,260,100)));
    const auto nearContour=normalize({{100,4},{399,4},{399,149},{100,149}},size);
    cas::Reference edgeReference(nearFrame.clone());edgeReference.setRoi(nearContour);
    auto edgeSettings=settings;edgeSettings.labelOutline=nearContour;
    cv::Mat clipped,up=(cv::Mat_<double>(2,3)<<1,0,0,0,1,-8);
    cv::warpAffine(nearFrame,clipped,up,size,cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(220,220,220));
    const auto incomplete=cas::inspectFrame(edgeReference,clipped,edgeSettings,true);
    std::cout<<"out of image: "<<incomplete.reason<<" shift="<<incomplete.shift<<'\n';
    require(incomplete.reject && incomplete.reason=="label_out_of_frame","missing camera pixels must reject as incomplete inspection");
    cv::Mat touching,touch=(cv::Mat_<double>(2,3)<<1,0,0,0,1,-4.5);
    cv::warpAffine(nearFrame,touching,touch,size,cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(220,220,220));
    const auto atBorder=cas::inspectFrame(edgeReference,touching,edgeSettings,true);
    const auto borderSelf=cas::inspectFrame(edgeReference,nearFrame,edgeSettings,true);
    require(!atBorder.reject && cv::norm(fullInspection(atBorder),fullInspection(borderSelf),cv::NORM_INF)==0,
        "available pixels next to the frame border must not acquire a second guard");
    edgeSettings.sensitivity=0;
    require(!cas::inspectFrame(edgeReference,clipped,edgeSettings,true).reject,"zero sensitivity still disables rejection");
    std::cout<<cases<<" directional ROI crossings, reference bounds, unchanged contour and frame limits passed\n";
 }catch(const std::exception& error) { std::cerr<<error.what()<<'\n';return 1; }
}
