package com.example.iml.orchestrator.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Tests must work on a clean checkout without the owner's ignored address file. */
public final class ShippedConfigFixture {
    public static Path copyTo(Path directory) throws Exception {
        Path source = Files.isDirectory(Path.of("config")) ? Path.of("config") : Path.of("../config");
        var keys = new TreeSet<String>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml") && !p.getFileName().toString().equals("ips.yaml")).toList()) {
                String text = Files.readString(path);
                var matcher = Pattern.compile("\\$\\{ip:([^}]+)}").matcher(text);
                while (matcher.find()) keys.add(matcher.group(1));
                Path destination = directory.resolve(source.relativize(path));
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, text);
            }
        }
        var addresses = new StringBuilder();
        int device = 1;
        for (String key : keys) addresses.append(key).append(": '")
                .append(key.startsWith("light_") ? "192.0.2." + device++ : "127.0.0.1").append("'\n");
        Files.writeString(directory.resolve("ips.yaml"), addresses);
        return directory;
    }
}
