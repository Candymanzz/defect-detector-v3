import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestJson {
  public static void main(String[] args) throws Exception {
    ObjectMapper m = new ObjectMapper();
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("shm_name", Path.of("D:\\visionGay\\defect-detector-v3\\logs\\iml_cam_0_frame.bin").toString());
    body.put("product_type", "bench-single-frame");
    body.put("width", 2448);
    System.out.println(new String(m.writeValueAsBytes(body)));
  }
}
