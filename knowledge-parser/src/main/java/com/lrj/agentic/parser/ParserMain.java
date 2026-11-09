package com.lrj.agentic.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.OcrProtocol;
import java.io.IOException;
import java.nio.file.Path;

/** 一次性 P03 入口；stdout 仅是协议输出，异常正文和内部路径不进入响应。 */
public final class ParserMain {
    private ParserMain() { }

    /** 命令只接固定格式和声明字节数，原文件从有界 stdin 传入。 */
    public static void main(String[] args) {
        int exit = Exit.INTERNAL.code();
        try {
            if (args.length != 2 && args.length != 3) {
                System.exit(Exit.INVALID.code());
                return;
            }
            ExtractionProtocol.Format format = ExtractionProtocol.Format.fromCode(args[0]);
            String parser = args.length == 3 ? args[2] : format.parser();
            boolean ocr = format == ExtractionProtocol.Format.PDF && OcrProtocol.PARSER.equals(parser);
            if (!ocr && !format.parser().equals(parser)) { throw new IllegalArgumentException(); }
            int size = Integer.parseInt(args[1]);
            if (size < 1 || size > ExtractionProtocol.MAX_SOURCE_BYTES) {
                System.exit(Exit.RESOURCE_LIMIT.code());
                return;
            }
            byte[] source = System.in.readNBytes(size + 1);
            if (source.length != size) {
                System.exit(Exit.INVALID.code());
                return;
            }
            var extraction = switch (format) {
                case PDF -> ocr ? new OcrPdfExtractor(configuredPath(OcrProtocol.EXECUTABLE_PROPERTY),
                        configuredPath(OcrProtocol.DATA_PROPERTY)).extract(source) : new PdfExtractor().extract(source);
                case DOCX -> new DocxExtractor().extract(source);
            };
            byte[] json = new ObjectMapper().writeValueAsBytes(extraction);
            if (json.length > ExtractionProtocol.MAX_OUTPUT_BYTES) {
                System.exit(Exit.RESOURCE_LIMIT.code());
                return;
            }
            System.out.write(json);
            System.out.flush();
            exit = Exit.SUCCESS.code();
        } catch (ParseFailure rejected) {
            exit = rejected.exit().code();
        } catch (OutOfMemoryError | StackOverflowError exhausted) {
            exit = Exit.RESOURCE_LIMIT.code();
        } catch (IllegalArgumentException malformed) {
            exit = Exit.INVALID.code();
        } catch (IOException | RuntimeException unavailable) {
            // 父进程只接收稳定退出码；禁止把输入内容或库内部异常序列化到 stdout。
            exit = Exit.INTERNAL.code();
        }
        System.exit(exit);
    }

    /** 未配置路径按不可用处理，不能隐式搜索PATH执行不确定版本。 */
    private static Path configuredPath(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
