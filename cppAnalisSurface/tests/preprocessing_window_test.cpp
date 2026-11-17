#include "core/preprocessing.h"
#include <iostream>
#include <stdexcept>
int main() {
 try {
    cv::setNumThreads(1);cv::RNG rng(7131);int checked=0;
    for(auto size:{cv::Size(512,384),cv::Size(123,87)}) for(int channels:{1,3}) {
        cv::Mat reference(size,CV_MAKETYPE(CV_8U,channels)),current(size,reference.type());
        rng.fill(reference,cv::RNG::UNIFORM,0,256);rng.fill(current,cv::RNG::UNIFORM,0,256);
        for(int shape=0;shape<5;++shape) {
            cv::Mat mask=cv::Mat::zeros(size,CV_8U);
            if(shape==0) cv::ellipse(mask,{size.width/2,size.height/2},{size.width/4,size.height/4},0,0,360,255,cv::FILLED);
            if(shape==1) cv::rectangle(mask,{0,0,size.width/2,size.height/2},255,cv::FILLED);
            if(shape==2) {cv::rectangle(mask,{size.width/4,size.height/4,size.width/2,size.height/2},255,cv::FILLED);cv::circle(mask,{size.width/2,size.height/2},9,0,cv::FILLED);}
            if(shape==3) cv::line(mask,{4,7},{size.width-5,7},255,1);
            if(shape==4) mask.setTo(255);
            const auto before=mask.clone();
            for(double scale:{1.,.5,.25,.51}) for(auto shift:{cv::Point2d(0,0),cv::Point2d(.2,-.3),cv::Point2d(-.2,.3),
                    cv::Point2d(3.25,-2.5),cv::Point2d(-3.25,2.5),cv::Point2d(.015625,-.015625),
                    cv::Point2d(22.25,-18.75)}) for(bool alreadyInset:{false,true}) {
                const auto work=cas::inspectionWorkSize(size,scale);cv::Mat denseReference,croppedReference;
                const auto dense=cas::alignRoiInspectionGray(current,reference,work,mask,shift,denseReference,alreadyInset,false);
                const auto cropped=cas::alignRoiInspectionGray(current,reference,work,mask,shift,croppedReference,alreadyInset,true);
                if(cv::norm(dense,cropped,cv::NORM_INF)!=0 || cv::norm(denseReference,croppedReference,cv::NORM_INF)!=0)
                    throw std::runtime_error("support window changed comparison pixels; shape="+std::to_string(shape)+" scale="+std::to_string(scale));
                if(cv::norm(mask,before,cv::NORM_INF)!=0) throw std::runtime_error("support cache mutated");
                ++checked;
            }
        }
    }
    std::cout<<checked<<" dense/windowed image and reference comparisons are pixel-exact\n";
 } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
