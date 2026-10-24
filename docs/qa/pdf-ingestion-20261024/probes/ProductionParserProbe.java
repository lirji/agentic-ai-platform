import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProcess;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.file.Files;
import java.nio.file.Path;

/** 验证真实父进程适配器和打包解析器；仅输出摘要，不打印业务原文或运行凭据。 */
public class ProductionParserProbe {
    public static void main(String[] args) throws Exception {
        var properties = new ParserProperties(true, false, Path.of(System.getProperty("java.home"), "bin", "java"), Path.of(args[0]).toAbsolutePath());
        var mapper = new ObjectMapper();
        var meters = new SimpleMeterRegistry();
        try {
            var parser = new ParserProcess(properties, new ProcessingJson(mapper), Tracer.NOOP, meters);
            try {
                var extracted = parser.extract(Files.readAllBytes(Path.of(args[1])));
                Files.write(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(extracted));
                System.out.println("blocks=" + extracted.blocks().size() + " digest=" + extracted.extractionDigest()
                        + " active=" + meters.get("knowledge.parser.active").gauge().value());
            } finally { parser.close(); }
        } finally { meters.close(); }
    }
}
