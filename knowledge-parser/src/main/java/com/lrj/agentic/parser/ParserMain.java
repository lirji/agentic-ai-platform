package com.lrj.agentic.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import java.io.IOException;

/** 一次性 P03 入口；stdout 仅是协议输出，异常正文和内部路径不进入响应。 */
public final class ParserMain {
    private ParserMain() { }

    /** 命令只接固定格式和声明字节数，原文件从有界 stdin 传入。 */
    public static void main(String[] args) {
        int exit = Exit.INTERNAL.code();
        try {
            if (args.length != 2) {
                System.exit(Exit.INVALID.code());
                return;
            }
            ExtractionProtocol.Format format = ExtractionProtocol.Format.fromCode(args[0]);
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
                case PDF -> new PdfExtractor().extract(source);
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
}
