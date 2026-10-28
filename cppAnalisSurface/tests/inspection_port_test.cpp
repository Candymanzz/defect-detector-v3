#include "core/inspector.h"
#include "core/seam.h"
#include "service/service.h"
#include <opencv2/imgproc.hpp>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
int checks=0;
void require(bool ok,const char* what){++checks;if(!ok)throw std::runtime_error(what);}
cv::Mat fixture(){cv::Mat image(321,383,CV_8UC3);cv::RNG(28).fill(image,cv::RNG::UNIFORM,20,235);return image;}
void settings(const std::filesystem::path& dir){
    cas::Settings p;
    require(cas::kScoreScale==500 && p.threshold==.6,"CppDetector/main gain and production threshold units must be preserved");
    require(p.withMetricDefaults(0).horizontalShiftMm==.75,"camera 0 uses source provisional scale");
    require(std::abs(*p.withMetricDefaults(4).verticalShiftMm-1.02)<1e-10,"camera 4 uses its own scale");
    require(std::abs(*p.withMetricDefaults(9).horizontalShiftMm-.84)<1e-10,"camera 9 uses its own scale");
    p=p.withMetricDefaults(4);
    require(p.applyJson({{"horizontal_shift_tolerance_mm",2.0},{"vertical_shift_tolerance_mm",1.5}}).empty(),"metric settings load");
    for(auto size:{cv::Size(1224,1024),cv::Size(2448,2048)}){
        const auto s=p.forFrame(size);const auto units=s.mmPerPixel(size);
        require(std::abs(*s.alignmentLimitX*units.x-2)<1e-10 && std::abs(*s.alignmentLimitY*units.y-1.5)<1e-10,
                "millimeters must follow digital image resolution");
    }
    const auto original=p.toJson();
    for(const auto& invalid:{cas::json(-1),cas::json(10.01),cas::json("bad"),cas::json(std::numeric_limits<double>::infinity())}){
        require(!p.applyJson({{"horizontal_shift_tolerance_mm",3},{"vertical_shift_tolerance_mm",invalid}}).empty(),"invalid metric values rejected");
        require(p.toJson()==original,"invalid metric updates atomic");
    }
    require(!p.applyJson({{"estimated_mm_per_pixel",{0,.3}}}).empty(),"zero scale rejected");
    require(p.toJson()==original,"bad scale does not mutate settings");
    cas::SettingsPatch::fromJson({{"horizontal_shift_tolerance_mm",0.5}}).applyTo(p);
    require(p.horizontalShiftMm==.5 && p.verticalShiftMm==1.5,"ephemeral mm patch preserves other axis");
    cas::SettingsPatch::fromJson({{"shift_tolerance_x",1.25}}).applyTo(p);
    require(!p.horizontalShiftMm && p.shiftToleranceX==1.25,"legacy patch retains fractional pixels and overrides metric axis");
    require(p.applyJson({{"calibration",{{"horizontal",{{"p0",{.1,.5}},{"p1",{.6,.5}},{"length_mm",48}}},
                                        {"vertical",{{"p0",{.5,.1}},{"p1",{.5,.6}},{"length_mm",80}}}}}}).empty(),"measured axis calibration accepted");
    require(p.calibration.ready() && std::abs(p.mmPerPixel({384,320}).x-.25)<1e-10,"calibration follows normalized spans");

    const auto file=dir/"settings.json";
    {std::ofstream f(file);f<<cas::json{{"a",{{"shift_tolerance",6},{"threshold",.4}}},{"untouched",{{"shift_tolerance",30}}}};}
    {
        cas::SettingsStore store(file);cas::Settings saved;
        require(store.set("a",{{"horizontal_shift_tolerance_mm",.5},{"vertical_shift_tolerance_mm",4}},saved,4).empty(),"camera metric save");
        require(saved.threshold==.4 && saved.horizontalShiftMm==.5,"threshold preserved during migration");
        require(store.set("b",{{"sensitivity",.8}},saved,1).empty(),"second profile save");
        require(std::abs(store.get("untouched",4).estimatedMmPerPixel.x-.27)<1e-10,"saving another profile must not pin a generic scale");
    }
    cas::SettingsStore restarted(file);
    const auto saved=restarted.get("a",4);
    require(saved.horizontalShiftMm==.5 && saved.verticalShiftMm==4 && saved.threshold==.4,"mm settings survive restart");
    require(std::abs(saved.estimatedMmPerPixel.x-.27)<1e-10,"camera scale survives reload");
    require(*restarted.get("untouched",4).verticalShiftMm<=10,"legacy migration remains in valid metric range");
}
void selfAlignment(){
    const auto image=fixture();
    for(int width:{59,91,101,137,191,257})for(bool vertical:{false,true}){
        cas::Reference ref(image.clone());
        cas::NormPolygon roi=vertical ? cas::NormPolygon{{30./382,30./320},{200./382,30./320},{200./382,(30.+width)/320},{30./382,(30.+width)/320}}
                                      : cas::NormPolygon{{30./382,30./320},{(30.+width)/382,30./320},{(30.+width)/382,200./320},{30./382,200./320}};
        ref.setRoi(roi);
        for(double scale:{1.,.7,.5,.2}){
            cas::Settings p;p.horizontalShiftMm=0;p.verticalShiftMm=0;p.inspectScale=scale;
            const auto cached=ref.prepared(scale);const auto before=cached->refSmall.clone();
            for(int repeat=0;repeat<3;++repeat){const auto r=cas::inspectFrame(ref,image,p,true);
                require(!r.reject && r.anomalyScore==0 && cv::norm(r.rawShift)<1e-5,"identical ROI must have zero shift at all scales");}
            require(cv::norm(before,cached->refSmall,cv::NORM_INF)==0,"padded cache remains immutable");
        }
    }
}
void pixelAreasAndScore(){
    cv::Mat image(320,384,CV_8UC3,cv::Scalar::all(130)),frame=image.clone();
    cv::circle(frame,{192,160},55,cv::Scalar::all(20),8);
    cas::Reference ref(image);cas::Settings p;p.sensitivity=1;p.shiftToleranceX=p.shiftToleranceY=0;p.minBlobArea=1;
    const auto r=cas::inspectFrame(ref,frame,p,true);
    cv::Mat expected;cv::threshold(r.diff,expected,r.pixelThreshold,255,cv::THRESH_BINARY);
    cv::morphologyEx(expected,expected,cv::MORPH_OPEN,cv::getStructuringElement(cv::MORPH_ELLIPSE,{3,3}));
    require(cv::norm(expected,r.kept,cv::NORM_INF)==0 && r.kept.at<uchar>(160,192)==0,"clean ring interior must remain unflagged");
    require(r.anomalyScore==std::min(1.,r.areaPercent/.2),"main formula retains the production score range and saturation");
    cv::Mat small=image.clone();cv::rectangle(small,{188,156,8,8},cv::Scalar::all(20),cv::FILLED);
    const auto smallResult=cas::inspectFrame(ref,small,p,true);
    require(smallResult.areaPercent>0 && smallResult.areaPercent<.2,"exercise unsaturated production score");
    require(std::abs(smallResult.anomalyScore-smallResult.areaPercent/.2)<1e-10,"main percent score must be normalised to 0..1");
    p.threshold=(smallResult.anomalyScore+1)/2;
    require(!cas::inspectFrame(ref,small,p,true).reject,"normalised score below threshold passes");
    p.threshold=smallResult.anomalyScore/2;
    const auto rejected=cas::inspectFrame(ref,small,p,true);
    require(rejected.reject && rejected.reason=="score_over_threshold","normalised score above threshold rejects");
    p.minBlobArea=cv::countNonZero(expected)+1;
    require(cas::inspectFrame(ref,frame,p,true).anomalyScore==0,"minimum area uses component pixels");
}
void service(const std::filesystem::path& dir){
    cv::Mat line(320,384,CV_8UC3,cv::Scalar::all(80));cv::line(line,{180,35},{180,285},cv::Scalar::all(250),4);
    cas::Service svc(dir/"service",4);svc.setReference("bucket#cam=4",line.clone());
    cas::Settings saved;require(svc.settings().set("custom",{{"threshold",1.0},{"seam",{{"enabled",true},{"p0",{180./384,40./320}},{"p1",{180./384,280./320}}}}},saved,4).empty(),"seam settings save");
    cas::InspectJob job;job.product="bucket#cam=4";job.profile="custom";
    const auto good=svc.inspectMat(job,line,true);
    require(good.out.seamFound && !good.out.seamReject,"straight seam passes");
    require(std::abs(good.settings.estimatedMmPerPixel.x-.27)<1e-10,"camera identity resolved independently of profile name");
    cv::Mat blank(line.size(),line.type(),cv::Scalar::all(80));const auto missing=svc.inspectMat(job,blank,true);
    require(!missing.out.seamFound && missing.out.seamReject && missing.out.reject,"enabled missing seam rejects");
    require(!missing.out.reason.empty(),"missing seam has explicit reason");
    bool invalid=false;try{svc.setRoi(job.product,{{.1,.1},{.2,.2},{.3,.3}});}catch(const std::invalid_argument&){invalid=true;}
    require(invalid,"zero-area ROI rejected");
    job.patch=cas::SettingsPatch::fromJson({{"horizontal_shift_tolerance_mm",2},{"vertical_shift_tolerance_mm",2},{"seam",{{"enabled",false}}}});
    for(double scale:{1.,.7,.5,.2}){
        job.patch.inspectScale=scale;const auto r=svc.inspectMat(job,line,true);const auto units=r.settings.mmPerPixel(line.size());
        require(std::abs(r.out.shiftLimit.x*units.x-2)<1e-9 && std::abs(r.out.shiftLimit.y*units.y-2)<1e-9,
                "service uses fixed physical limits independent of analysis scale");
    }
    cv::Mat low(320,384,CV_8UC3,cv::Scalar::all(80));cv::line(low,{180,35},{180,285},cv::Scalar::all(95),4);
    cas::SeamParams seam;seam.enabled=true;seam.p0={180,40};seam.p1={180,280};seam.minContrast=5;
    require(cas::measureSeam(low,seam).found,"low configured contrast accepted");seam.minContrast=30;
    require(!cas::measureSeam(low,seam).found,"seam contrast reaches measurement");
}
}
int main(){
    namespace fs=std::filesystem;cv::setNumThreads(1);
    const auto dir=fs::temp_directory_path()/("inspection-port-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try{fs::create_directories(dir);settings(dir);selfAlignment();pixelAreasAndScore();service(dir);
        const auto resolved=fs::weakly_canonical(dir);
        if(resolved.parent_path()==fs::weakly_canonical(fs::temp_directory_path()) && resolved.filename().string().rfind("inspection-port-",0)==0)fs::remove_all(resolved);
        std::cout<<"production inspection port passed: "<<checks<<" checks\n";return 0;
    }catch(const std::exception&e){std::cerr<<e.what()<<" (test data "<<dir.string()<<")\n";return 1;}
}
