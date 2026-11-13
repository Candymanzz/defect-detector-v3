#include "core/inspector.h"
#include <opencv2/imgproc.hpp>
#include <iostream>
#include <stdexcept>

void require(bool ok,const char* text) { if(!ok) throw std::runtime_error(text); }
int main() {
 try {
    cv::setNumThreads(1);
    cv::Mat reference(384,512,CV_8UC3,cv::Scalar(220,220,220)),texture(reference.size(),reference.type());
    cv::RNG rng(52);rng.fill(texture,cv::RNG::UNIFORM,15,130);
    const std::vector<cv::Point> points{{60,100},{130,65},{260,50},{390,65},{450,100},{430,310},{260,340},{80,310}};
    cv::Mat mask=cv::Mat::zeros(reference.size(),CV_8U);cv::fillPoly(mask,std::vector<std::vector<cv::Point>>{points},255);
    texture.copyTo(reference,mask);
    cas::Settings settings;settings.horizontalShiftMm=30;settings.verticalShiftMm=30;settings.minBlobArea=4;
    for(auto p:points) settings.labelOutline.emplace_back(p.x/511.,p.y/383.);
    cas::Reference ref(reference.clone());
    cv::Mat inset;cv::erode(mask,inset,cv::getStructuringElement(cv::MORPH_RECT,{3,3}));
    for(double scale:{1.,.5}) for(int tolerance:{0,3,5}) {
        auto s=settings;s.inspectScale=scale;s.shiftToleranceX=s.shiftToleranceY=tolerance;
        auto out=cas::inspectFrame(ref,reference,s,true);
        require(out.labelEnabled&&!out.reject,"self inspection");
        if(scale==1.) require(cv::norm(out.inspectionMask,inset,cv::NORM_INF)==0,"exact 1px inset independent of relaxation");
        else require(out.labelCoverage==1.,"half-scale guard rounds to zero");
    }
    int checks=0;
    for(double scale:{1.,.5}) for(auto shift:{cv::Point(0,0),cv::Point(0,8),cv::Point(0,-8),cv::Point(8,0),cv::Point(-8,0),cv::Point(8,-8)}) {
        auto s=settings;s.inspectScale=scale;
        cv::Mat frame,warp=(cv::Mat_<double>(2,3)<<1,0,shift.x,0,1,shift.y);
        cv::warpAffine(reference,frame,warp,reference.size(),cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(220,220,220));
        auto out=cas::inspectFrame(ref,frame,s,true);
        require(!out.reject&&out.anomalyScore==0,"shifted label and exposed bucket pass");
        cv::rectangle(frame,{240+shift.x,170+shift.y,30,25},{0,0,0},cv::FILLED);
        require(cas::inspectFrame(ref,frame,s,true).reject,"central defect remains visible");++checks;
    }
    const auto prepared=ref.prepared(1.,{},settings.labelOutline);
    const auto before=prepared->roiMask.clone();
    const cv::Rect learnedBox(200,150,40,40);
    ref.setLearned({{"normal",learnedBox,reference(learnedBox).clone()}});
    for(int i=0;i<20;++i) {
        auto out=cas::inspectFrame(ref,reference,settings,true);require(!out.reject,"repeat self");
        require(ref.prepared(1.,{},settings.labelOutline)==prepared,"stable contour preparation cache");
    }
    require(cv::norm(before,prepared->roiMask,cv::NORM_INF)==0,"cached mask must not shrink");
    settings.labelTrackingEnabled=false;
    auto off=cas::inspectFrame(ref,reference,settings,true);
    auto original=settings;original.labelOutline.clear();
    auto baseline=cas::inspectFrame(ref,reference,original,true);
    require(!off.labelEnabled&&off.anomalyScore==baseline.anomalyScore,"disabled matches original inspection");
    require(!settings.labelOutline.empty(),"disable retains contour");
    settings.labelTrackingEnabled=true;settings.labelReferenceFingerprint="different-reference";
    require(cas::inspectFrame(ref,reference,settings,true).reason=="label_reference_changed","reference replacement requires confirmation");
    settings.sensitivity=0;require(!cas::inspectFrame(ref,reference,settings,true).reject,"zero sensitivity disables rejection");
    auto json=settings.toJson();cas::Settings restored;
    require(restored.applyJson(json).empty()&&restored.labelOutline==settings.labelOutline,"contour round trip");
    require(!restored.applyJson({{"label_tracking_enabled","true"}}).empty(),"strict boolean validation");
    require(!restored.applyJson({{"label_outline",{{-1,0},{1,0},{1,1}}}}).empty(),"strict contour validation");
    restored.labelReferenceFingerprint="bound";
    require(restored.applyJson({{"label_reference_fingerprint",""}}).empty() && restored.labelReferenceFingerprint=="bound",
        "stale empty client fingerprint must not clear reference binding");
    require(restored.applyJson({{"label_confirmation_id","new-confirmation"}}).empty() && restored.labelReferenceFingerprint.empty(),
        "explicit fresh confirmation resets reference binding");
    auto patch=cas::SettingsPatch::fromJson({{"label_tracking_enabled",false}});patch.applyTo(restored);
    require(!restored.labelTrackingEnabled,"test override supports toggle");
    std::cout<<checks<<" shift/defect pairs, cache, toggle, reference identity and settings passed\n";
 }catch(const std::exception& e) { std::cerr<<e.what()<<'\n';return 1; }
}
