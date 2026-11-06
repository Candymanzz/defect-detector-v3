#include "core/inspector.h"
#include "core/preprocessing.h"
#include <opencv2/imgproc.hpp>
#include <future>
#include <iostream>
#include <stdexcept>

namespace {
int checks = 0;
void require(bool value, const std::string& message) {
    ++checks;
    if (!value) throw std::runtime_error(message);
}

struct Fixture {
    cv::Size size{641, 479};
    cas::NormPolygon polygon;
    cv::Mat mask, texture;
    explicit Fixture(bool tilted = false, bool mono = false) {
        const std::vector<cv::Point> points = tilted
            ? std::vector<cv::Point>{{80,70},{550,55},{565,410},{65,420}}
            : std::vector<cv::Point>{{80,65},{559,65},{559,414},{80,414}};
        mask = cv::Mat::zeros(size, CV_8U);
        cv::fillPoly(mask, std::vector<std::vector<cv::Point>>{points}, 255);
        for (const auto& p : points)
            polygon.emplace_back(double(p.x)/(size.width-1), double(p.y)/(size.height-1));
        texture = cv::Mat(size, CV_8UC3, cv::Scalar::all(125));
        cv::RNG random(20261106);
        for (int i=0; i<180; ++i) {
            const int x=random.uniform(120,510), y=random.uniform(105,355);
            cv::rectangle(texture,{x,y,random.uniform(6,18),random.uniform(6,18)},
                cv::Scalar::all(random.uniform(15,235)),cv::FILLED);
        }
        cv::putText(texture,"MOVING LABEL",{155,260},cv::FONT_HERSHEY_SIMPLEX,1,{235,235,235},2);
        if (mono) cv::cvtColor(texture,texture,cv::COLOR_BGR2GRAY);
    }
    cv::Mat scene(cv::Point2d shift = {}, int outside = 230, const cv::Rect& defect = {}, int value = 0) const {
        auto source=texture.clone();
        if (!defect.empty()) source(defect).setTo(cv::Scalar::all(value));
        const cv::Mat transform=(cv::Mat_<double>(2,3)<<1,0,shift.x,0,1,shift.y);
        cv::Mat moved, selected;
        cv::warpAffine(source,moved,transform,size,cv::INTER_LINEAR,cv::BORDER_REPLICATE);
        cv::warpAffine(mask,selected,transform,size,cv::INTER_NEAREST,cv::BORDER_CONSTANT,0);
        cv::Mat result(size,texture.type(),cv::Scalar::all(outside));
        // Background markings stay fixed while only the label moves.
        for (int x=0; x<size.width; x+=32)
            cv::rectangle(result,{x,0,10,size.height},cv::Scalar::all(45),cv::FILLED);
        moved.copyTo(result,selected);
        return result;
    }
    cas::Settings settings(double scale) const {
        cas::Settings s;
        s.inspectScale=scale;
        s.calibration.horizontal={{0,0},{1,0},size.width*.25};
        s.calibration.vertical={{0,0},{0,1},size.height*.25};
        s.horizontalShiftMm=s.verticalShiftMm=4.5;
        return s;
    }
};

void movingLabel() {
    for (bool mono : {false,true}) for (bool tilted : {false,true}) {
        Fixture fixture(tilted,mono);
        auto image=fixture.scene();
        cas::Reference reference(image.clone()); reference.setRoi(fixture.polygon);
        const auto fingerprint=reference.fingerprint();
        for (double scale : {.2,.5,.73,1.}) {
            auto s=fixture.settings(scale);
            const auto cached=reference.prepared(scale);
            const auto weights=cached->roiWeights.clone();
            for (double sensitivity : {.5,.8}) for (const cv::Point2d shift : {cv::Point2d(0,0),{0,-2},{0,2},{0,-8},{0,8},{0,-16},{0,16},{-4,2},{4,-2}}) {
                s.sensitivity=sensitivity;
                auto current=fixture.scene(shift); const auto source=current.clone();
                const auto out=cas::inspectFrame(reference,current,s,true);
                const auto context=" scale="+std::to_string(scale)+" dy="+std::to_string(shift.y)
                    +" tilted="+std::to_string(tilted)+" mono="+std::to_string(mono)
                    +" score="+std::to_string(out.anomalyScore);
                require(!out.reject && out.anomalyScore < .05,"moving label falsely rejected"+context);
                require(out.shift==out.rawShift && out.shiftResponse>.3,"permitted translation was not applied"+context);
                require(out.roiArea==cached->roiArea,"ROI coverage changed"+context);
                require(cv::norm(current,source,cv::NORM_INF)==0,"source pixels modified"+context);
                require(cv::countNonZero(cas::fullMask(out) & (cached->roiMask==0))==0,
                    "heatmap mask escaped ROI"+context);
            }
            require(cv::norm(cached->roiWeights,weights,cv::NORM_INF)==0,"cached weights mutated");
        }
        require(reference.fingerprint()==fingerprint,"reference pixels modified");
    }
}

void edgeDefectsAndLimits() {
    Fixture fixture;
    cas::Reference reference(fixture.scene()); reference.setRoi(fixture.polygon);
    // Notches reach each exact ROI boundary; no permanently excluded border is allowed.
    const cv::Rect defects[]={{300,65,30,16},{300,399,30,16},{80,220,16,30},{544,220,16,30}};
    for (double scale : {.2,.5,.73,1.}) for (double dy : {-8.,8.}) {
        auto settings=fixture.settings(scale);
        for (const auto& defect : defects) for (int value : {0,255}) {
            const auto current=fixture.scene({0,dy},230,defect,value);
            const auto out=cas::inspectFrame(reference,current,settings,true);
            const std::string context=" scale="+std::to_string(scale)+" dy="+std::to_string(dy)
                +" edge="+std::to_string(defect.x)+","+std::to_string(defect.y)
                +" value="+std::to_string(value)+" score="+std::to_string(out.anomalyScore);
            require(out.reject && out.reason=="score_over_threshold","edge defect missed"+context);
            require(cv::countNonZero(cas::fullMask(out))>0,"edge defect missing from heatmap"+context);
        }
        settings.verticalShiftMm=.5;
        const auto exceeded=cas::inspectFrame(reference,fixture.scene({0,dy}),settings,false);
        require(exceeded.reject && exceeded.reason=="shift_exceeded" && cv::norm(exceeded.shift)==0,
            "above-limit label translation must still reject");
    }
}

void singlePixelAndConcurrency() {
    Fixture fixture(false,true);
    cas::Reference reference(fixture.scene()); reference.setRoi(fixture.polygon);
    auto settings=fixture.settings(1);
    const auto cached=reference.prepared(1);
    auto source=fixture.scene(); const auto before=source.clone();
    const auto clean=cas::prepareRoiInspectionGray(source,source.size(),cached->nativeRoiMask,cached->roiWeights);
    source.at<uchar>(65,320)=255;
    const auto defect=cas::prepareRoiInspectionGray(source,source.size(),cached->nativeRoiMask,cached->roiWeights);
    require(defect.at<uchar>(65,320)>clean.at<uchar>(65,320),"outermost label pixel excluded from filtering");
    require(source.at<uchar>(65,320)==255 && before.at<uchar>(65,320)==125,"mono filtering overwrote source");
    auto run=[&] { return cas::inspectFrame(reference,before,settings,false); };
    auto first=std::async(std::launch::async,run), second=std::async(std::launch::async,run);
    require(first.get().anomalyScore==0 && second.get().anomalyScore==0,"concurrent self inspection is unstable");
}

// Render on an independent 4x grid and integrate to camera pixels. The native
// edge contains mixtures of the moving label and stationary background; using
// the inspector's own native nearest-neighbour mask would miss this regression.
struct IntegratedScene {
    Fixture fixture;
    cv::Mat texture, mask, background;
    explicit IntegratedScene(bool tilted) : fixture(tilted) {
        const cv::Size high(fixture.size.width*4, fixture.size.height*4);
        cv::resize(fixture.texture,texture,high,0,0,cv::INTER_LINEAR);
        cv::resize(fixture.mask,mask,high,0,0,cv::INTER_NEAREST);
        cv::Mat outside(fixture.size,CV_8UC3,cv::Scalar::all(230));
        for (int x=0;x<outside.cols;x+=32)
            cv::rectangle(outside,{x,0,10,outside.rows},cv::Scalar::all(45),cv::FILLED);
        cv::resize(outside,background,high,0,0,cv::INTER_NEAREST);
    }
    cv::Mat render(double dy, bool mono = false, const cv::Rect& defect = {}, int value = 0) const {
        auto source=texture;
        if (!defect.empty()) {
            source=source.clone();
            source(cv::Rect(defect.x*4,defect.y*4,defect.width*4,defect.height*4)).setTo(cv::Scalar::all(value));
        }
        const cv::Mat m=(cv::Mat_<double>(2,3)<<1,0,0,0,1,dy*4);
        cv::Mat moved,selected,result;
        cv::warpAffine(source,moved,m,source.size(),cv::INTER_LINEAR,cv::BORDER_REPLICATE);
        cv::warpAffine(mask,selected,m,source.size(),cv::INTER_NEAREST,cv::BORDER_CONSTANT,0);
        auto scene=background.clone(); moved.copyTo(scene,selected);
        cv::resize(scene,result,fixture.size,0,0,cv::INTER_AREA);
        if (mono) cv::cvtColor(result,result,cv::COLOR_BGR2GRAY);
        return result;
    }
};

void fractionalBoundary() {
    for (bool tilted : {false,true}) for (bool mono : {false,true}) {
        IntegratedScene scene(tilted);
        cas::Reference reference(scene.render(0,mono)); reference.setRoi(scene.fixture.polygon);
        for (double scale : {.2,.5,.73,1.}) {
            const auto cached=reference.prepared(scale);
            const auto before=cached->refComparisonGray.clone();
            auto s=scene.fixture.settings(scale);
            for (double sensitivity : {.5,.8}) for (double dy : {-15.75,-8.5,-2.25,2.25,8.5,15.75}) {
                s.sensitivity=sensitivity;
                const auto out=cas::inspectFrame(reference,scene.render(dy,mono),s,false);
                const auto context=" scale="+std::to_string(scale)+" dy="+std::to_string(dy)
                    +" tilted="+std::to_string(tilted)+" mono="+std::to_string(mono);
                require(!out.reject,"mixed boundary falsely rejected"+context);
                require(std::abs(out.rawShift.y-dy)<.2,"positioning regressed"+context);
            }
            require(cv::norm(cached->refComparisonGray,before,cv::NORM_INF)==0,"comparison cache mutated");
            require(cas::inspectFrame(reference,scene.render(0,mono),s,false).anomalyScore==0,
                    "self comparison changed after compensation");
        }
    }
    IntegratedScene scene(false);
    cas::Reference reference(scene.render(0)); reference.setRoi(scene.fixture.polygon);
    const cv::Rect edges[]={{300,65,30,8},{300,407,30,8},{80,220,8,30},{552,220,8,30}};
    for (double scale : {.5,1.}) for (double dy : {-8.5,8.5}) for (const auto& edge : edges) for (int value : {0,255}) {
        auto s=scene.fixture.settings(scale);
        const auto out=cas::inspectFrame(reference,scene.render(dy,false,edge,value),s,true);
        require(out.reject && out.reason=="score_over_threshold","integrated edge damage missed");
        require(cv::countNonZero(cas::fullMask(out))>0,"integrated damage absent from heatmap");
    }
    auto s=scene.fixture.settings(.5);
    s.verticalShiftMm=.5;
    const auto current=scene.render(8.5);
    const auto over=cas::inspectFrame(reference,current,s,false);
    require(over.reject && over.reason=="shift_exceeded","above-limit shift accepted");
    s.sensitivity=0;
    const auto disabled=cas::inspectFrame(reference,current,s,false);
    require(!disabled.reject && disabled.anomalyScore==0 && disabled.reason.empty(),
            "zero sensitivity must disable shift rejection too");
    s=scene.fixture.settings(.5); s.sensitivity=.8;
    const auto before=reference.prepared(.5)->refComparisonGray.clone();
    auto run=[&](double dy){return cas::inspectFrame(reference,scene.render(dy),s,false);};
    auto first=std::async(std::launch::async,run,2.25);
    auto second=std::async(std::launch::async,run,-8.5);
    require(!first.get().reject && !second.get().reject,"concurrent compensation changed comparison");
    require(cv::norm(before,reference.prepared(.5)->refComparisonGray,cv::NORM_INF)==0,
            "concurrent compensation mutated shared cache");
    cv::Mat narrow=cv::Mat::zeros({31,31},CV_8U);
    narrow(cv::Rect(15,5,1,21)).setTo(255);
    cv::Mat light(narrow.size(),CV_8U,cv::Scalar(180)), dark(narrow.size(),CV_8U,cv::Scalar(40));
    cv::Mat preparedReference;
    const auto compared=cas::alignRoiInspectionGray(light,dark,narrow.size(),narrow,{.25,.25},preparedReference);
    require(cv::norm(compared,preparedReference,cv::NORM_INF)>100,
            "a narrow ROI became two empty comparison images");
}
}

int main() {
    try {
        cv::setNumThreads(1);
        movingLabel(); edgeDefectsAndLimits(); singlePixelAndConcurrency(); fractionalBoundary();
        std::cout<<"label ROI passed: "<<checks<<" checks\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr<<error.what()<<'\n'; return 1;
    }
}
