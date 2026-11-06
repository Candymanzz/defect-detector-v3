#include "core/exclusion_zones.h"
#include "core/seam.h"
#include "service/service.h"
#include <opencv2/imgcodecs.hpp>
#include <chrono>
#include <future>
#include <fstream>
#include <iostream>

namespace {
void require(bool value, const char* text) { if (!value) throw std::runtime_error(text); }
const cas::NormPolygons hole{{{90./383,90./319},{190./383,90./319},{190./383,190./319},{90./383,190./319}}};
cv::Mat reference() {
    cv::Mat grey(320,384,CV_8U);cv::RNG rng(42);rng.fill(grey,cv::RNG::UNIFORM,40,210);
    cv::Mat image;cv::cvtColor(grey,image,cv::COLOR_GRAY2BGR);return image;
}
cv::Mat defect(const cv::Mat& ref, bool outside=false) {
    auto image=ref.clone();cv::rectangle(image,{115,115,50,50},{0,0,0},cv::FILLED);
    if(outside) cv::rectangle(image,{240,220,45,45},{0,0,0},cv::FILLED);return image;
}
void validateAndPersist(const std::filesystem::path& dir) {
    cas::Settings s;const auto json=cas::exclusionsJson(hole);
    require(s.applyJson({{"exclusions",json}}).empty(),"valid normalized polygons");
    const auto before=s.toJson();
    for(const auto& bad : {cas::json(nullptr),cas::json("bad"),cas::json{{{0,0},{1,1}}},cas::json{{{-1,0},{1,0},{1,1}}},cas::json{{{0,0},{2,0},{1,1}}},cas::json{{{0,0},{.5,.5},{1,1}}}}) {
        require(!s.applyJson({{"threshold",.2},{"exclusions",bad}}).empty(),"reject malformed zones");
        require(s.toJson()==before,"invalid patch is atomic");
        bool rejected=false;try{cas::SettingsPatch::fromJson({{"exclusions",bad}});}catch(const std::invalid_argument&){rejected=true;}
        require(rejected,"invalid temporary exclusion cannot silently fall back to saved zones");
    }
    cas::json many=cas::json::array();for(int i=0;i<33;++i)many.push_back(json[0]);
    require(!s.applyJson({{"exclusions",many}}).empty(),"polygon count limit");
    auto points=cas::json::array();for(int i=0;i<129;++i)points.push_back({i%2,0});
    require(!s.applyJson({{"exclusions",cas::json::array({points})}}).empty(),"vertex count limit");
    const auto file=dir/"settings.json";
    {cas::SettingsStore store(file);cas::Settings saved;require(store.set("cam-0",{{"exclusions",json}},saved).empty(),"persist zones");}
    {cas::SettingsStore store(file);require(store.get("cam-0").exclusions==hole,"zones survive settings restart");
     require(store.get("cam-1").exclusions.empty(),"profile isolation");cas::Settings saved;
     require(store.set("cam-0",{{"threshold",.3}},saved).empty()&&saved.exclusions==hole,"unrelated settings preserve zones");
     require(store.set("cam-0",{{"exclusions",cas::json::array()}},saved).empty()&&saved.exclusions.empty(),"empty array clears zones");}
}
void inspectionAndConcurrency() {
    auto base=reference(),inside=defect(base),outside=defect(base,true);cas::Reference ref(base);
    const cas::NormPolygon roi{{30./383,30./319},{350./383,30./319},{350./383,290./319},{30./383,290./319}};
    ref.setRoi(roi);cas::Settings s;s.threshold=.1;s.minBlobArea=3;
    for(double scale:{1.,.73,.5}) {
        s.inspectScale=scale;s.exclusions.clear();require(cas::inspectFrame(ref,inside,s,true).reject,"inside defect rejects before masking");
        const auto original=ref.prepared(scale);s.exclusions=hole;
        const auto result=cas::inspectFrame(ref,inside,s,true);
        require(!result.reject&&result.anomalyScore==0,"inside defect ignored at all scales");
        require(result.roiArea<original->roiArea,"excluded pixels removed from score denominator");
        require(cas::inspectFrame(ref,outside,s,true).reject,"outside defect still rejects at all scales");
        const auto masked=ref.prepared(scale,hole);
        require(masked->alignment->exclusions==hole,"native alignment cache must belong to the same exclusions as comparison");
        const auto alignment=masked->alignment;
        const int coarseX=cvRound((140-alignment->region.x)/alignment->coarseToNative.x);
        const int coarseY=cvRound((140-alignment->region.y)/alignment->coarseToNative.y);
        require(alignment->hann.at<float>(coarseY,coarseX)==0,"ignored pixels must stay outside the coarse phase window");
        const int fineX=cvRound((140-alignment->region.x)/alignment->fineToNative.x);
        const int fineY=cvRound((140-alignment->region.y)/alignment->fineToNative.y);
        require(alignment->fineMask.at<uchar>(fineY,fineX)==0,"ignored pixels must stay outside ECC refinement");
        require(cv::norm(masked->upperCornerMask,original->upperCornerMask,cv::NORM_INF)==0,"interior hole does not relocate corners");
        auto duplicated=hole;duplicated.push_back(hole[0]);require(ref.prepared(scale,duplicated)->roiArea==masked->roiArea,"overlap is union");
        const auto visual=cas::inspectFrame(ref,outside,s,true);const auto heat=cas::buildHeatmap(visual,roi,128);
        const auto mask=cas::exclusionMask(hole,heat.size());cv::Mat selected;heat.copyTo(selected,mask);
        require(cv::countNonZero(selected)==0,"decorative heatmap halo removed from holes");
    }
    s.inspectScale=1;s.exclusions=hole;
    cv::Mat shifted;cv::Mat matrix=(cv::Mat_<double>(2,3)<<1,0,3,0,1,2);
    cv::warpAffine(inside,shifted,matrix,inside.size(),cv::INTER_LINEAR,cv::BORDER_REPLICATE);
    require(!cas::inspectFrame(ref,shifted,s,true).reject,"zones remain attached to reference after translation");
    std::vector<std::future<void>> requests;
    for(int worker=0;worker<4;++worker) requests.push_back(std::async(std::launch::async,[&,worker]{
        cas::Settings local=s;if(worker%2==0)local.exclusions.clear();
        for(int i=0;i<12;++i) require(cas::inspectFrame(ref,inside,local,true).reject==(worker%2==0),"concurrent mask variants must not leak across requests");
    }));
    for(auto& request:requests)request.get();
    s.exclusions={{{0,0},{1,0},{1,1},{0,1}}};s.sensitivity=1;
    const auto skipped=cas::inspectFrame(ref,outside,s,true);
    require(skipped.surfaceSkipped&&!skipped.reject&&skipped.anomalyScore==0&&skipped.reason=="inspection_excluded","full ROI exclusion overrides forced sensitivity rejection");
    require(ref.prepared(1)->roiArea>0,"default prepared call has no mutable exclusion state");
    cas::Reference freshCache(base);freshCache.setRoi(roi);
    auto withHole=freshCache.prepared(.5,hole);
    require(freshCache.prepared(.73,hole)->alignment==withHole->alignment,"same exclusions reuse native positioning across inspection scales");
    require(freshCache.prepared(.73)->alignment->exclusions.empty(),"another mask cannot inherit the previous alignment variant");
    cv::Mat odd(337,391,CV_8UC3);cv::RNG(73).fill(odd,cv::RNG::UNIFORM,40,210);cas::Reference oddRef(odd);
    s.exclusions=hole;s.sensitivity=.5;
    for(double scale:{.21,.73,1.}) {
        s.inspectScale=scale;
        const auto self=cas::inspectFrame(oddRef,odd,s,true);
        require(!self.reject&&self.anomalyScore==0&&cv::countNonZero(self.diff)==0,"odd-size masked self-comparison preserves shared preprocessing");
    }
}
void seamAndService(const std::filesystem::path& dir) {
    cv::Mat image(320,384,CV_8UC3,cv::Scalar(40,40,40));
    for(int y=20;y<300;++y)cv::line(image,{180+(y>=135&&y<155?5:0),y},{183+(y>=135&&y<155?5:0),y},{240,240,240},1);
    cas::Service svc(dir/"service",4);svc.setReference("bucket#cam=0",image);
    const cas::NormPolygons zones{{{140./383,110./319},{230./383,110./319},{230./383,180./319},{140./383,180./319}}};
    cas::Settings saved;
    require(svc.settings().set("camera-0",{{"seam",{{"enabled",true},{"p0",{181.5/384,20./320}},{"p1",{181.5/384,300./320}},{"band",15},{"tol_mid",.8},{"tol_edge",.8}}}},saved).empty(),"seam settings");
    cas::InspectJob job;job.product="bucket#cam=0";job.profile="camera-0";
    require(svc.inspectMat(job,image,true).out.seamReject,"negative control seam kink");
    require(svc.settings().set("camera-0",{{"exclusions",cas::exclusionsJson(zones)}},saved).empty(),"save seam exclusion");
    auto result=svc.inspectMat(job,image,true);require(result.out.seamFound&&!result.out.reject,"excluded seam section ignored in fits and verdict");
    job.patch=cas::SettingsPatch::fromJson({{"exclusions",cas::json::array()}});
    require(svc.inspectMat(job,image,true).out.seamReject,"temporary empty zones restore seam checking");
    require(svc.settings().get("camera-0").exclusions==zones,"temporary override does not persist");
    job.patch={};svc.setRoi(job.product,{{.38,.37},{.58,.37},{.58,.54},{.38,.54}});
    // Surface ROI is inside a zone, but the rest of the seam remains independently active.
    cv::Mat outside=image.clone();for(int y=215;y<235;++y){outside.row(y).setTo(cv::Scalar(40,40,40));cv::line(outside,{186,y},{189,y},{240,240,240},1);}
    result=svc.inspectMat(job,outside,true);
    require(result.out.surfaceSkipped&&result.out.seamReject&&result.out.reason=="seam_deviation","surface skip must not bypass a seam defect outside zones");
}
}
int main(int argc,char** argv) {
    cv::setNumThreads(1);
    if(argc==3&&std::string(argv[1])=="--fixtures") {
        const std::filesystem::path dir(argv[2]);std::filesystem::create_directories(dir);auto base=reference();
        cv::imwrite((dir/"reference.png").string(),base);cv::imwrite((dir/"inside.png").string(),defect(base));cv::imwrite((dir/"outside.png").string(),defect(base,true));return 0;
    }
    const auto dir=std::filesystem::temp_directory_path()/("cas-exclusion-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        std::filesystem::create_directories(dir);validateAndPersist(dir);inspectionAndConcurrency();seamAndService(dir);
        require(std::filesystem::weakly_canonical(dir).parent_path()==std::filesystem::weakly_canonical(std::filesystem::temp_directory_path()),"safe cleanup path");
        std::filesystem::remove_all(dir);std::cout<<"Exclusion settings, scoring, concurrency, translation, heatmap, seam and persistence passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<" (data: "<<dir.string()<<")\n";return 1;}
}
