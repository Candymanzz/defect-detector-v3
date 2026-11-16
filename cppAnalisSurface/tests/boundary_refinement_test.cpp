#include "core/inspector.h"
#include "core/label_tracking.h"
#include <opencv2/imgproc.hpp>
#include <iostream>
#include <stdexcept>

void require(bool value,const char* text) { if(!value) throw std::runtime_error(text); }
int main() {
 try {
    cv::setNumThreads(1);
    cv::Mat reference(384,512,CV_8UC3,cv::Scalar(220,220,220));
    std::vector<cv::Point> polygon;cas::NormPolygon contour;
    for(int i=0;i<48;++i) {
        const double a=2*CV_PI*i/48;
        cv::Point p(cvRound(256+160*std::cos(a)),cvRound(192+120*std::sin(a)));
        polygon.push_back(p);contour.emplace_back(p.x/511.,p.y/383.);
    }
    cv::Mat original=cv::Mat::zeros(reference.size(),CV_8U),inner;
    cv::fillPoly(original,std::vector<std::vector<cv::Point>>{polygon},255);
    reference.setTo(cv::Scalar(40,70,110),original);
    cv::erode(original,inner,cv::getStructuringElement(cv::MORPH_ELLIPSE,{35,35}));
    cv::Mat texture(reference.size(),reference.type());cv::RNG rng(71);rng.fill(texture,cv::RNG::UNIFORM,30,170);
    texture.copyTo(reference,inner);
    const auto self=cas::refineLabelBoundary(reference,reference,contour,{});
    require(self.nativeMask.empty() && self.status=="unchanged","identical boundary must not trim anything extra");
    cv::Mat movedMask;
    cv::erode(original,movedMask,cv::getStructuringElement(cv::MORPH_ELLIPSE,{7,7}));
    auto frame=reference.clone();frame.setTo(cv::Scalar(220,220,220),original & ~movedMask);
    const auto fit=cas::refineLabelBoundary(reference,frame,contour,{});
    std::cout<<"3px band: "<<fit.status<<" candidates="<<fit.candidates<<" inliers="<<fit.inliers<<" inset="<<fit.maxInset<<'\n';
    require(!fit.nativeMask.empty() && fit.status=="refined","coherent exposed bucket strip must refine");
    require(fit.maxInset>1.5 && fit.maxInset<=5,"adaptive inset must follow the measured edge within search limits");
    require(cv::countNonZero(fit.nativeMask)<cv::countNonZero(original),"mask must remove exposed bucket");
    std::vector<cv::Mat> normalFrames{frame};
    for(int side=0;side<4;++side) {
        std::vector<cv::Point> changed;
        for(auto p:polygon) {
            if(side==0) p.y=cvRound(312+(p.y-312)*.99);
            if(side==1) p.y=cvRound(72+(p.y-72)*.99);
            if(side==2) p.x=cvRound(416+(p.x-416)*.99);
            if(side==3) p.x=cvRound(96+(p.x-96)*.99);
            changed.push_back(p);
        }
        cv::Mat actual=cv::Mat::zeros(reference.size(),CV_8U);
        cv::fillPoly(actual,std::vector<std::vector<cv::Point>>{changed},255);
        auto exposed=reference.clone();exposed.setTo(cv::Scalar(220,220,220),original & ~actual);
        const auto edge=cas::refineLabelBoundary(reference,exposed,contour,{});
        require(!edge.nativeMask.empty(),"coherent top/bottom/side edge must refine");
        normalFrames.push_back(exposed);
    }
    cv::Mat fractional,warp=(cv::Mat_<double>(2,3)<<1,0,2.3,0,1,-1.4);
    cv::warpAffine(frame,fractional,warp,reference.size(),cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(220,220,220));
    normalFrames.push_back(fractional);
    auto damaged=frame.clone();cv::rectangle(damaged,{246,72,20,14},{220,220,220},cv::FILLED);
    const auto damagedFit=cas::refineLabelBoundary(reference,damaged,contour,{});
    require(!damagedFit.nativeMask.empty() && damagedFit.nativeMask.at<unsigned char>(82,256)!=0,
        "a narrow notch must remain inside inspection rather than reshape the contour");
    const auto absent=cas::refineLabelBoundary(reference,cv::Mat(reference.size(),reference.type(),cv::Scalar(220,220,220)),contour,{});
    require(absent.nativeMask.empty(),"unreliable boundary must retain the original contour");
    cv::Mat flat(reference.size(),reference.type(),cv::Scalar(220,220,220));
    require(cas::refineLabelBoundary(flat,flat,contour,{}).nativeMask.empty(),"weak contrast must not infer a crop");
    cv::Mat excluded(reference.size(),CV_8U,cv::Scalar(255));
    require(cas::refineLabelBoundary(reference,frame,contour,{},excluded).nativeMask.empty(),"excluded edge points must not affect refinement");
    cas::Reference ref(reference.clone());cas::Settings settings;
    settings.labelOutline=contour;settings.horizontalShiftMm=30;settings.verticalShiftMm=30;
    settings.minBlobArea=20;settings.threshold=.1;
    for(int depth:{2,3,4}) {
        auto tiny=frame.clone();cv::Mat damage=cv::Mat::zeros(original.size(),CV_8U);
        for(int x=248;x<264;++x) {
            int y=0;while(y<movedMask.rows && !movedMask.at<unsigned char>(y,x)) ++y;
            for(int d=0;d<depth && y+d<movedMask.rows;++d) {
                tiny.at<cv::Vec3b>(y+d,x)={220,220,220};damage.at<unsigned char>(y+d,x)=255;
            }
        }
        const auto result=cas::inspectFrame(ref,tiny,settings,true);
        const int visible=cv::countNonZero(result.inspectionMask & damage);
        std::cout<<"top notch depth="<<depth<<" visible="<<visible<<" score="<<result.anomalyScore<<'\n';
        require(visible>=int(16*(depth-1)*.8),"top-edge notch must not be cropped beyond the fixed one-pixel guard");
        require(result.anomalyScore>0 && cv::countNonZero(result.diff & damage)>0,
            "2-4px top-edge damage must produce an anomaly, not be fitted away");
    }
    for(double scale:{1.,.5}) {
        settings.inspectScale=scale;
        const auto prepared=ref.prepared(scale,{},contour);
        const auto maskBefore=prepared->nativeRoiMask.clone(),grayBefore=prepared->refComparisonGray.clone();
        for(size_t i=0;i<normalFrames.size();++i) {
            auto normal=cas::inspectFrame(ref,normalFrames[i],settings,true);
            std::cout<<"scale="<<scale<<" case="<<i<<" status="<<normal.labelBoundaryStatus<<" score="<<normal.anomalyScore<<" reason="<<normal.reason<<'\n';
            require(normal.labelBoundaryStatus=="refined" && !normal.reject,"white strip must pass after refinement");
            auto scratched=normalFrames[i].clone();cv::rectangle(scratched,{230,160,35,30},{0,0,0},cv::FILLED);
            require(cas::inspectFrame(ref,scratched,settings,true).reject,"interior defects remain detectable after refinement");
        }
        auto defect=cas::inspectFrame(ref,damaged,settings,true);
        require(defect.reject && defect.anomalyScore>0,"notch must remain detectable with adaptive masking");
        require(cv::norm(maskBefore,prepared->nativeRoiMask,cv::NORM_INF)==0 &&
            cv::norm(grayBefore,prepared->refComparisonGray,cv::NORM_INF)==0,"refinement must not mutate cached reference pixels or support");
        settings.labelTrackingEnabled=false;
        auto disabled=cas::inspectFrame(ref,frame,settings,true);
        require(disabled.labelBoundaryStatus=="disabled" && disabled.inspectionMask.empty(),"switch off must bypass refinement and cropping");
        settings.labelTrackingEnabled=true;
    }
    std::cout<<"boundary refinement, notch preservation, fallback and switch checks passed\n";
 }catch(const std::exception& error) { std::cerr<<error.what()<<'\n';return 1; }
}
