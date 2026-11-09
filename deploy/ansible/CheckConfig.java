import java.nio.file.Path;
import com.example.iml.orchestrator.config.YamlFileConfigLoader;

class CheckConfig {
    public static void main(String[] args) throws Exception {
        var config = new YamlFileConfigLoader().load(Path.of(args[0]));

        if (config == null || config.isEmpty()) {
            throw new IllegalStateException("Configuration is empty");
        }
        System.out.println("Configuration loaded successfully");
    }
}