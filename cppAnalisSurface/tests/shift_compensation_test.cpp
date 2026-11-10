#include "shift_scene.h"
#include "core/comparison_mask.h"
#include "service/service.h"
#include <atomic>
#include <filesystem>
#include <future>
#include <iostream>
#include <stdexcept>

namespace {
std::atomic<int> checks{0};
void require(bool value,const std::string& message){++checks;if(!value)throw std::runtime_error(message);}
void geometry() {
    ShiftScene scene;cas::Reference reference(scene.render());reference.setRoi(scene.roi);
    auto prepared=reference.prepared(1);
    const auto original=prepared->roiMask.clone();
    const auto exact=cas::comparisonMask(*prepared,{6,-3},1,{1,1});
    require(exact.active&&exact.status=="applied","reliable shift must activate interior comparison");
    require(exact.cropNative==cv::Point(6,3),"geometric inset differs from native shift");
    require(cv::boundingRect(exact.mask)==cv::Rect(89,71,462,338),"symmetric native inset plus sampling/Gaussian guards");
    require(cv::norm(original,prepared->roiMask,cv::NORM_INF)==0,"mask construction mutated cached ROI");
    const auto zero=cas::comparisonMask(*prepared,{},1,{7,7});
    require(!zero.active&&zero.mask.data==prepared->roiMask.data,"zero shift must preserve the exact old mask");
    require(!cas::comparisonMask(*prepared,{4,4},.2,{7,7}).active,"weak estimate must not shrink inspection");
    require(!cas::comparisonMask(*prepared,{.1,-.1},1,{7,7}).active,"microscopic estimate must not crop learned edge samples");
    require(cas::comparisonMask(*prepared,{.1,8},1,{1,1}).cropNative.x==0,"cross-axis jitter became a full-pixel inset");
    require(!cas::comparisonMask(*prepared,{NAN,4},1,{1,1}).active,"non-finite estimate accepted");
    const auto huge=cas::comparisonMask(*prepared,{1e20,0},1,{1,1});
    require(huge.status=="insufficient_comparison_area"&&huge.area==0,"huge shift must be bounded and fail safely");
    const cas::NormPolygons hole{{{.45,.40},{.55,.40},{.55,.60},{.45,.60}}};
    const auto withHole=reference.prepared(1,hole);
    const auto croppedHole=cas::comparisonMask(*withHole,{6,-3},1,{1,1});
    cv::Mat expected=exact.mask.clone();expected.setTo(0,withHole->roiMask==0);
    require(cv::norm(expected,croppedHole.mask,cv::NORM_INF)==0,"automatic crop expanded a permanent hole");
    ShiftScene tilted(true);cas::Reference poly(tilted.render());poly.setRoi(tilted.roi);
    const auto polygon=cas::comparisonMask(*poly.prepared(1),{6,-3},1,{1,1});
    require(cv::countNonZero(polygon.mask & (poly.prepared(1)->roiMask==0))==0,"polygon bounding box leaked into background");
    require(polygon.area<cv::boundingRect(polygon.mask).area(),"tilted ROI replaced by a rectangle");
}
void scenes() {
    for(bool tilted:{false,true}) {
        ShiftScene scene(tilted);const auto base=scene.render();cas::Reference reference(base.clone());reference.setRoi(scene.roi);
        for(double scale:{.2,.5,.73,1.}) {
            const auto prepared=reference.prepared(scale);const auto mask=prepared->roiMask.clone();
            auto settings=scene.settings(scale);
            const auto self=cas::inspectFrame(reference,base,settings,true);
            require(!self.reject&&self.anomalyScore==0&&!self.comparisonCropped&&self.roiArea==prepared->roiArea,"self comparison must not crop or reject");
            for(int relaxation:{3,5})for(const auto shift:std::vector<cv::Point2d>{{0,-8.5},{0,8.5},{-8.5,0},{8.5,0},{4,-8},{-4,8}}) {
                settings.shiftToleranceX=settings.shiftToleranceY=relaxation;
                settings.sensitivity=.8;
                const auto current=scene.render(shift);const auto before=current.clone();
                const auto result=cas::inspectFrame(reference,current,settings,true);
                const auto context=" scale="+std::to_string(scale)+" tilt="+std::to_string(tilted)+" shift="+
                    std::to_string(shift.x)+","+std::to_string(shift.y)+" score="+std::to_string(result.anomalyScore)+
                    " raw="+std::to_string(result.rawShift.x)+","+std::to_string(result.rawShift.y);
                require(!result.reject&&result.anomalyScore<.05,"clean moving label falsely rejected"+context);
                require(result.comparisonCropped&&result.comparisonStatus=="applied","expected interior mode"+context);
                // Layer motion may retain the existing coarse phase estimate;
                // shift_accuracy_test covers precision on its controlled inputs.
                // Here verify tracking; before/after probes check estimator parity.
                require(cv::norm(result.rawShift-shift)<2,"positioning failed to track the label"+context);
                require(result.inspectedFraction>=.5&&result.inspectedFraction<1,"invalid inspected fraction"+context);
                require(result.roiArea==cv::countNonZero(result.comparisonMask),"score denominator does not match actual mask");
                require(cv::countNonZero(result.diff & (result.comparisonMask==0))==0,"difference escaped comparison mask");
                const auto heat=cas::buildHeatmap(result,scene.roi,0);
                require(cv::countNonZero(heat(result.region) & (result.comparisonMask==0))==0,"heatmap halo escaped comparison mask");
                require(cv::norm(before,current,cv::NORM_INF)==0,"caller pixels changed");
                const auto bad=cas::inspectFrame(reference,scene.render(shift,{290,210,32,28}),settings,false);
                require(bad.reject&&bad.reason=="score_over_threshold","retained middle defect missed"+context);
            }
            require(reference.prepared(scale)==prepared&&cv::norm(mask,prepared->roiMask,cv::NORM_INF)==0,"per-frame crop replaced/mutated shared cache");
        }
    }
}
void bandsAndLimits() {
    ShiftScene scene;const auto base=scene.render();cas::Reference reference(base.clone());reference.setRoi(scene.roi);
    for(double scale:{.2,.5,1.})for(double dy:{-8.,8.}) {
        auto settings=scene.settings(scale);settings.shiftToleranceX=settings.shiftToleranceY=0;
        const auto band=scene.render({0,dy},{180,410,220,5},255);
        const auto out=cas::inspectFrame(reference,band,settings,true);
        require(!out.reject&&out.anomalyScore==0,"white margin inside the excluded bottom strip affected verdict");
        settings.verticalShiftMm=.5;
        const auto exceeded=cas::inspectFrame(reference,band,settings,false);
        require(exceeded.reject&&exceeded.reason=="shift_exceeded"&&!exceeded.comparisonCropped,"crop hid above-limit displacement");
        settings.sensitivity=0;
        require(!cas::inspectFrame(reference,band,settings,false).reject,"zero sensitivity must disable all rejection");
    }
    cas::Reference full(base.clone());
    const auto out=cas::inspectFrame(full,scene.render({0,8}),scene.settings(.5),false);
    require(!out.comparisonCropped&&out.comparisonStatus=="label_roi_required","full-frame mode should retain old path");
    reference.setRoi({{.25,.15},{.27,.15},{.27,.85},{.25,.85}});
    auto settings=scene.settings(.5);settings.shiftToleranceX=settings.shiftToleranceY=0;
    const auto small=cas::inspectFrame(reference,scene.render({0,8}),settings,true);
    require(small.reject&&small.reason=="insufficient_comparison_area"&&small.surfaceSkipped,"insufficient automatic area returned PASS");
    settings.sensitivity=0;
    require(!cas::inspectFrame(reference,scene.render({0,8}),settings,false).reject,"zero sensitivity did not override insufficient area");
    settings=scene.settings(.5);settings.exclusions={{{0,0},{1,0},{1,1},{0,1}}};settings.sensitivity=1;
    const auto excluded=cas::inspectFrame(reference,scene.render({0,8}),settings,false);
    require(!excluded.reject&&excluded.reason=="inspection_excluded","manual full exclusion changed behavior");
}
void learningAndConcurrency() {
    ShiftScene scene;const auto base=scene.render();cas::Reference reference(base.clone());reference.setRoi(scene.roi);
    const cv::Rect center(270,190,80,80),edge(180,392,70,23);
    reference.setLearned({{"center",center,base(center).clone()},{"edge",edge,base(edge).clone()}});
    auto run=[&](double scale) {
        auto settings=scene.settings(scale);settings.sensitivity=.8;
        for(int i=0;i<4;++i){
            const auto good=cas::inspectFrame(reference,scene.render({0,8.5}),settings,false);
            require(!good.reject,"learning changed clean verdict");
            const auto bad=cas::inspectFrame(reference,scene.render({0,-8.5},{290,210,32,28}),settings,false);
            require(bad.reject,"learning suppressed new middle damage");
            bool skipped=false;
            for(const auto& d:bad.learningDiagnostics)if(d["id"]=="edge")skipped=d["status"]=="outside_comparison_area";
            require(skipped,"learning used cropped margin as evidence");
        }
    };
    auto half=reference.prepared(.5),full=reference.prepared(1);
    auto one=std::async(std::launch::async,run,.5),two=std::async(std::launch::async,run,1.);
    one.get();two.get();
    require(reference.prepared(.5)==half&&reference.prepared(1)==full,"learning evicted preparation cache");
}
}
int main(){try{cv::setNumThreads(1);geometry();scenes();bandsAndLimits();learningAndConcurrency();
    std::cout<<"shift interior compensation passed: "<<checks<<" checks\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
