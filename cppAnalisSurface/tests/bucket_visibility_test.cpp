#include "core/inspector.h"
#include "core/bucket_visibility.h"
#include <opencv2/imgproc.hpp>
#include <iostream>
#include <stdexcept>

void require(bool ok,const char* message) { if(!ok) throw std::runtime_error(message); }
cas::NormPolygon normalize(const std::vector<cv::Point>& points,cv::Size size) {
    cas::NormPolygon result;for(auto p:points) result.emplace_back(p.x/double(size.width-1),p.y/double(size.height-1));return result;
}
cv::Mat moved(const cv::Mat& image,cv::Point2d shift,int interpolation=cv::INTER_LINEAR) {
    cv::Mat result,warp=(cv::Mat_<double>(2,3)<<1,0,shift.x,0,1,shift.y);
    cv::warpAffine(image,result,warp,image.size(),interpolation,cv::BORDER_CONSTANT,0);return result;
}
int main() {
 try {
    cv::setNumThreads(1);const cv::Size size(512,384);
    std::vector<cv::Point> polygon;
    for(int i=0;i<64;++i) {
        const double a=2*CV_PI*i/64;polygon.emplace_back(cvRound(256+175*std::cos(a)),cvRound(192+140*std::sin(a)));
    }
    auto contour=normalize(polygon,size);
    cv::Mat bucket=cv::Mat::zeros(size,CV_8U);cv::fillPoly(bucket,std::vector<std::vector<cv::Point>>{polygon},255);
    cv::Mat background(size,CV_8UC3,cv::Scalar(12,18,22));
    cv::Mat paint(size,CV_8UC3,cv::Scalar(80,100,140)),texture(size,CV_8UC3);
    cv::RNG rng(71);cv::Mat coarse(48,64,CV_8UC3);rng.fill(coarse,cv::RNG::UNIFORM,40,180);
    cv::resize(coarse,texture,size,0,0,cv::INTER_CUBIC);
    cv::Mat inner;cv::erode(bucket,inner,cv::getStructuringElement(cv::MORPH_ELLIPSE,{9,9}));texture.copyTo(paint,inner);
    cv::putText(paint,"PRINT",{145,185},cv::FONT_HERSHEY_SIMPLEX,1.3,{230,240,230},4);
    cv::line(paint,{165,230},{340,230},{10,20,10},8);
    cv::Mat reference=background.clone();paint.copyTo(reference,bucket);
    cas::Reference ref(reference.clone());ref.setRoi(contour);
    cas::Settings settings;settings.bucketOutline=contour;settings.labelOutline=contour;
    settings.horizontalShiftMm=4;settings.verticalShiftMm=4;settings.threshold=.1;settings.minBlobArea=20;
    const auto cached=ref.prepared(1.,{},contour);const auto maskBefore=cached->nativeRoiMask.clone();
    auto scene=[&](cv::Point2d body,cv::Point2d label,bool damage=false) {
        auto mask=moved(bucket,body,cv::INTER_NEAREST);
        auto source=paint.clone();if(damage) cv::rectangle(source,{245,53,22,8},{0,0,0},cv::FILLED);
        auto image=background.clone();auto pixels=moved(source,label);pixels.copyTo(image,mask);return image;
    };
    for(auto body:{cv::Point2d(8,-5),cv::Point2d(-7,6)}) {
        const auto motion=cas::estimateBucketTranslation(*ref.bucketPrepared(contour),cas::prepareInspectionGray(scene(body,body+cv::Point2d(4,0)),size));
        std::cout<<"body-only measured="<<motion.shift<<" expected="<<body<<" support="<<motion.support<<" residual="<<motion.residual<<'\n';
        require(motion.trusted && cv::norm(motion.shift-body)<.5,"physical edge tracker follows bucket motion separately");
    }
    int cases=0;
    for(double scale:{.5,1.}) for(auto body:{cv::Point2d(0,0),cv::Point2d(3,-2),cv::Point2d(-2,3)})
      for(auto relative:{cv::Point2d(0,0),cv::Point2d(4,0),cv::Point2d(-4,0),cv::Point2d(0,4)}) {
        auto s=settings;s.inspectScale=scale;
        const auto out=cas::inspectFrame(ref,scene(body,body+relative),s,true);
        std::cout<<"scale="<<scale<<" body="<<body<<" measured="<<out.bucketShift<<" label="<<out.shift
            <<" score="<<out.anomalyScore<<" reason="<<out.reason<<" status="<<out.bucketStatus<<'\n';
        require(out.bucketTracked && cv::norm(out.bucketShift-body)<.5,"bucket motion must be independent of the printing");
        require(!out.reject && out.anomalyScore==0,"common visible surface must pass without a white strip");
        require(out.labelBoundaryStatus=="bucket_geometry","new geometry must bypass adaptive label-boundary refinement");
        ++cases;
      }
    // Whole bucket movement must not be mistaken for relative label displacement.
    auto narrow=settings;narrow.horizontalShiftMm=.5;narrow.verticalShiftMm=.5;
    auto whole=cas::inspectFrame(ref,scene({4,-3},{4,-3}),narrow,true);
    require(!whole.reject && cv::norm(whole.relativeLabelShift)<.5,"mm tolerances use label displacement relative to the bucket");
    // A local edge injury is not used to shrink the tracked physical silhouette.
    auto damaged=cas::inspectFrame(ref,scene({0,0},{0,0},true),settings,true);
    require(damaged.bucketTracked && damaged.reject && damaged.anomalyScore>0,"visible edge damage must remain inspectable");
    require(cv::norm(maskBefore,cached->nativeRoiMask,cv::NORM_INF)==0,"geometry must not mutate the reference cache");
    require(ref.bucketPrepared(contour)==ref.bucketPrepared(contour),"bucket geometry has a stable reference cache");
    // A tighter inspection ROI must not hide a visible label injury outside its old current-frame position.
    std::vector<cv::Point> labelPoints;
    for(int i=0;i<64;++i) {
        const double a=2*CV_PI*i/64;labelPoints.emplace_back(cvRound(256+150*std::cos(a)),cvRound(192+110*std::sin(a)));
    }
    const auto labelContour=normalize(labelPoints,size);
    cas::Reference smaller(reference.clone());smaller.setRoi(labelContour);
    auto local=settings;local.labelOutline=labelContour;
    auto edgeFrame=scene({0,0},{0,-4});cv::rectangle(edgeFrame,{245,78,22,8},{0,0,0},cv::FILLED);
    auto edge=cas::inspectFrame(smaller,edgeFrame,local,true);
    require(edge.bucketTracked && edge.reject && edge.anomalyScore>0,"new bucket geometry preserves a defect beyond the old ROI");
    require(edge.inspectionMask.at<unsigned char>(85-edge.region.y,256-edge.region.x)!=0,"visible shifted edge stays inside the inspection mask");
    // Missing physical edges must not abort surface inspection when print
    // positioning remains reliable. The full-frame contour has no usable rim.
    auto fallbackSettings=local;
    fallbackSettings.bucketOutline={{0,0},{1,0},{1,1},{0,1}};
    const auto smallCache=smaller.prepared(1.,{},labelContour);
    const auto smallMask=smallCache->nativeRoiMask.clone();
    for(double scale:{.5,1.}) {
        fallbackSettings.inspectScale=scale;
        auto fallback=cas::inspectFrame(smaller,reference,fallbackSettings,true);
        require(fallback.bucketEnabled && !fallback.bucketTracked && fallback.bucketFallbackUsed,
            "unobservable bucket motion selects label positioning fallback");
        require(fallback.bucketStatus=="unreliable" && fallback.labelBoundaryStatus=="bucket_position_fallback",
            "fallback is explicit and never refines the confirmed label edge");
        require(!fallback.reject && fallback.reason.empty() && fallback.anomalyScore==0 && fallback.roiArea>0
            && !fallback.inspectionMask.empty() && !fallback.aligned.empty(),
            "unknown bucket motion alone is not a defect and comparison actually runs");
        for(auto delta:{cv::Point2d(0,-4),cv::Point2d(0,4),cv::Point2d(3,0),cv::Point2d(-3,0)}) {
            auto shifted=cas::inspectFrame(smaller,scene({0,0},delta),fallbackSettings,true);
            require(shifted.bucketFallbackUsed && shifted.shiftRefined && cv::norm(shifted.shift-delta)<.3,
                "ordinary label positioning continues in both axes");
            require(!shifted.reject && shifted.anomalyScore==0,"label fallback compares the shifted print");
        }
        auto injured=cas::inspectFrame(smaller,edgeFrame,fallbackSettings,true);
        require(injured.bucketFallbackUsed && injured.reject && injured.reason=="score_over_threshold"
            && injured.anomalyScore>0 && !injured.zones.empty(),"fallback preserves edge defect detection");
        require(injured.inspectionMask.at<unsigned char>(cvRound(85*scale)-injured.region.y,cvRound(256*scale)-injured.region.x)!=0,
            "fallback must not erase the shifted edge defect");
        auto limited=fallbackSettings;limited.horizontalShiftMm=.5;limited.verticalShiftMm=.5;
        auto over=cas::inspectFrame(smaller,scene({0,0},{4,0}),limited,true);
        require(over.bucketFallbackUsed && over.reject && over.reason=="shift_exceeded",
            "unknown bucket uses ordinary global shift limit rather than a fake relative zero");
        limited.sensitivity=0;
        require(!cas::inspectFrame(smaller,scene({0,0},{4,0}),limited,true).reject,
            "zero sensitivity still overrides fallback shift rejection");
    }
    require(cv::norm(smallMask,smallCache->nativeRoiMask,cv::NORM_INF)==0,"fallback does not mutate cached contour");
    // Flat data cannot be accepted as reliable print positioning either.
    const cv::Mat flat(size,CV_8UC3,cv::Scalar(100,100,100));cas::Reference weak(flat.clone());weak.setRoi(contour);
    auto unreliable=cas::inspectFrame(weak,flat,settings,true);
    require(unreliable.bucketFallbackUsed && unreliable.reject && unreliable.reason=="label_position_unreliable",
        "unknown print positioning remains a separate failure after the bucket fallback");
    auto off=settings;off.labelTrackingEnabled=false;
    require(!cas::inspectFrame(ref,reference,off,true).bucketEnabled,"master switch disables all bucket cutting");
    auto stale=settings;stale.bucketReferenceFingerprint="old";
    require(cas::inspectFrame(ref,reference,stale,true).reason=="bucket_reference_changed","bucket geometry is bound to its reference");
    stale.sensitivity=0;require(!cas::inspectFrame(ref,reference,stale,true).reject,"zero sensitivity still disables rejection");
    auto json=settings.toJson();cas::Settings restored;
    require(restored.applyJson(json).empty() && restored.bucketOutline==contour,"bucket settings round trip");
    require(!restored.applyJson({{"bucket_outline",{{-1,0},{1,0},{1,1}}}}).empty(),"bucket polygon validation");
    restored.bucketReferenceFingerprint="bound";
    require(restored.applyJson({{"bucket_reference_fingerprint",""}}).empty() && restored.bucketReferenceFingerprint=="bound",
        "an old empty client fingerprint cannot erase the bucket reference binding");
    require(restored.applyJson({{"bucket_confirmation_id","fresh"}}).empty() && restored.bucketReferenceFingerprint.empty(),
        "explicit bucket confirmation resets only its own reference binding");
    std::cout<<cases<<" independent bucket/print cases and regressions passed\n";
 } catch(const std::exception& e) { std::cerr<<e.what()<<'\n';return 1; }
}
