package com.example.iml.positioning.analysis;

import com.example.iml.positioning.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import static org.junit.jupiter.api.Assertions.*;

class StablePrintTranslationTest {
    @BeforeAll static void load() { OpenCvNativeLoader.ensureLoaded(); }

    private static Mat print() {
        Mat image = new Mat(480, 640, CvType.CV_8UC3, new Scalar(180, 180, 180));
        java.util.Random random = new java.util.Random(45);
        for (int i = 0; i < 200; i++) {
            int x = 40 + random.nextInt(550), y = 30 + random.nextInt(410);
            Imgproc.putText(image, Integer.toString(i), new Point(x,y), Imgproc.FONT_HERSHEY_SIMPLEX, .4,
                    new Scalar(30, 50, 80), 1, Imgproc.LINE_AA, false);
        }
        return image;
    }
    private static Mat moved(Mat image, double dx, double dy) {
        Mat warp = Mat.eye(2,3,CvType.CV_64F), out = new Mat();
        try {
            warp.put(0,2,dx); warp.put(1,2,dy);
            Imgproc.warpAffine(image,out,warp,image.size(),Imgproc.INTER_LINEAR,Core.BORDER_REPLICATE,new Scalar(0));
            return out;
        } finally { warp.release(); }
    }
    @Test void recoversSmallTranslationWithLocalizedDefectAndBrightnessChange() {
        Mat reference=print(), current=moved(reference,1.8,-1.2);
        try {
            Core.add(current,new Scalar(8,8,8),current);
            Imgproc.rectangle(current,new Point(115,105),new Point(190,150),new Scalar(90,90,90),-1);
            var estimate=StablePrintTranslation.estimate(reference,current,new Rect(0,0,640,480),null);
            assertNotNull(estimate);
            assertEquals(-1.8,estimate.dx(),.25); assertEquals(1.2,estimate.dy(),.25);
            assertTrue(estimate.supporters()>=4);
        } finally { reference.release();current.release(); }
    }
    @Test void localDeformationCannotMoveWholeArticle() {
        Mat reference=print(), current=reference.clone(), shifted=moved(reference,3,0);
        Rect damage=new Rect(110,100,120,110);
        Mat from=new Mat(shifted,damage), to=new Mat(current,damage);
        try {
            from.copyTo(to);
            assertNull(StablePrintTranslation.estimate(reference,current,new Rect(0,0,640,480),null));
        } finally { from.release();to.release();shifted.release();reference.release();current.release(); }
    }
    @Test void flatOrExcessivelyShiftedFramesAbstain() {
        Mat reference=new Mat(480,640,CvType.CV_8UC3,new Scalar(180,180,180));
        Mat current=reference.clone();
        try { assertNull(StablePrintTranslation.estimate(reference,current,new Rect(0,0,640,480),null)); }
        finally { reference.release();current.release(); }
        reference=print();current=moved(reference,9,0);
        try { assertNull(StablePrintTranslation.estimate(reference,current,new Rect(0,0,640,480),null)); }
        finally { reference.release();current.release(); }
    }

    @Test void serviceKeepsTransformDirectionAndRefreshesReferenceBetweenFrames() {
        var service=new BucketPositioningService(null); // writeAligned=false: never touch SHM.
        var request=new com.example.iml.positioning.dto.PositioningRequest(
                new com.example.iml.positioning.dto.RoiRect(0,0,640,480),null,.02,1,1,"",false);
        Mat reference=print();
        try {
            for (int i=0;i<3;i++) {
                Mat next=reference.clone();
                Imgproc.circle(next,new Point(320,240),25 + i*5,new Scalar(70,90,110),-1);
                Mat current=moved(next,i==1 ? -2 : 1.8,i==1 ? 1.5 : -1.2);
                try {
                    var result=service.position(next,current,request,"reference-"+i);
                    assertTrue(result.overallPass(),result.diagnostics().toString());
                    assertEquals(i==1 ? -2 : 1.8,result.homographyRefToCurrent()[2],.7);
                    assertEquals(i==1 ? 1.5 : -1.2,result.homographyRefToCurrent()[5],.7);
                    assertTrue(!result.alignedWritten());
                } finally { next.release();current.release(); }
            }
        } finally { reference.release(); }
    }
}
