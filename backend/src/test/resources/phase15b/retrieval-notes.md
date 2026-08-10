# 混合检索说明

平台采用同一条检索主链：

- BM25 关键词检索
- Semantic Search 向量检索
- Reranker 重排序

| 阶段 | 作用 |
| --- | --- |
| Child | 精确召回与 Citation 锚点 |
| Parent | 补充上下文 |

## 权限边界

Evidence、Parent 和 Citation 在返回前都要复核当前 ACL 与 Revision。

```text
BM25 + Vector -> RRF -> Rerank -> Evidence
```
