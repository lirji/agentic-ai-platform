-- 身份是不透明标识；保留原字节、字段注释和全部记录，只收紧比较语义。
-- 0900_bin区分大小写/重音且NO PAD，不让不同主体共享ACL、回执或反馈记录。
ALTER TABLE knowledge_document_share
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
ALTER TABLE knowledge_sharing_command
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
ALTER TABLE knowledge_feedback
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
