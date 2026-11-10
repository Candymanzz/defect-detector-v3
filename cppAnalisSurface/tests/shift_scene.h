#pragma once
#include "core/inspector.h"
#include <opencv2/imgproc.hpp>

// Independently render a moving label on a stationary background at 4x camera
// resolution. Integrating to camera pixels includes mixed fractional boundaries.
struct ShiftScene {
    cv::Size size{641,479};
    cas::NormPolygon roi;
    cv::Mat mask,texture,background;
    explicit ShiftScene(bool tilted=false) {
        const std::vector<cv::Point> points=tilted
            ? std::vector<cv::Point>{{80,70},{550,55},{565,410},{65,420}}
            : std::vector<cv::Point>{{80,65},{559,65},{559,414},{80,414}};
        cv::Mat nativeMask=cv::Mat::zeros(size,CV_8U);
        cv::fillPoly(nativeMask,std::vector<std::vector<cv::Point>>{points},255);
        for(const auto& p:points)roi.emplace_back(double(p.x)/(size.width-1),double(p.y)/(size.height-1));
        cv::Mat label(size,CV_8UC3,cv::Scalar::all(105));cv::RNG random(20261110);
        for(int i=0;i<170;++i){
            int x=random.uniform(120,510),y=random.uniform(105,355);
            cv::rectangle(label,{x,y,random.uniform(6,18),random.uniform(6,18)},
                cv::Scalar::all(random.uniform(15,235)),cv::FILLED);
        }
        cv::putText(label,"LABEL 36",{160,260},cv::FONT_HERSHEY_SIMPLEX,1,{235,235,235},2);
        cv::Mat outside(size,CV_8UC3,cv::Scalar::all(245));
        for(int x=0;x<size.width;x+=32)cv::rectangle(outside,{x,0,10,size.height},cv::Scalar::all(30),cv::FILLED);
        const cv::Size high(size.width*4,size.height*4);
        cv::resize(nativeMask,mask,high,0,0,cv::INTER_NEAREST);
        cv::resize(label,texture,high,0,0,cv::INTER_LINEAR);
        cv::resize(outside,background,high,0,0,cv::INTER_NEAREST);
    }
    cv::Mat render(cv::Point2d shift={},cv::Rect defect={},int value=0,bool mono=false)const {
        auto source=texture;
        if(!defect.empty()){
            source=source.clone();source(cv::Rect(defect.x*4,defect.y*4,defect.width*4,defect.height*4)).setTo(cv::Scalar::all(value));
        }
        const cv::Mat transform=(cv::Mat_<double>(2,3)<<1,0,shift.x*4,0,1,shift.y*4);
        cv::Mat moved,selected,result;
        cv::warpAffine(source,moved,transform,source.size(),cv::INTER_LINEAR,cv::BORDER_REPLICATE);
        cv::warpAffine(mask,selected,transform,mask.size(),cv::INTER_NEAREST,cv::BORDER_CONSTANT,0);
        auto scene=background.clone();moved.copyTo(scene,selected);
        cv::resize(scene,result,size,0,0,cv::INTER_AREA);
        if(mono)cv::cvtColor(result,result,cv::COLOR_BGR2GRAY);
        return result;
    }
    cas::Settings settings(double scale)const {
        cas::Settings s;s.inspectScale=scale;
        s.calibration.horizontal={{0,0},{1,0},size.width*.25};
        s.calibration.vertical={{0,0},{0,1},size.height*.25};
        s.horizontalShiftMm=s.verticalShiftMm=4.5;
        return s;
    }
};
