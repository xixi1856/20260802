# ADR-0002：PostgreSQL 与 PostGIS 作为空间数据源

状态：已接受

使用 PostgreSQL 与 PostGIS 保存 WGS84 轨迹、问题点和核验盲道路段。点使用 geography，线使用 geometry，并建立 GiST 索引。Redis 只承担在线 TTL、限流和短时缓存，不作为空间事实来源。
