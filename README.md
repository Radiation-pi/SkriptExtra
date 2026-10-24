# SkriptExtra

Skript 扩展。Java 21+，需要 Skript；WorldEdit、PlaceholderAPI、PacketEvents、skript-reflect 为可选插件。JDBC 变量存储已在 Skript 2.16.2 验证。

## 构建

```sh
./gradlew build
```

输出 `build/libs/SkriptExtra-1.1.1.jar`。JAR 内含 MariaDB JDBC Driver 3.5.6，其余服务器 API 为 `compileOnly`；不需要 SQLibrary 或另外安装驱动。

也可以使用服务器已安装的依赖进行离线兼容构建：

```sh
./gradlew --offline -PlocalServerDir=/absolute/server/path build
```

此模式用于验证指定服务器的 API，不能据此推断其他版本兼容性。

`build` 同时执行本地恢复日志的独立测试；数据库集成测试需要显式运行 `JdbcWriterIntegration`，仅限可丢弃的测试库。

## JDBC 全局变量存储

此功能接入 Skript 的变量存储 SPI。普通 `set {全局变量}`、列表变量及删除会继续使用 Skript 的序列化，通过后台单写入器批量提交。读取走 Skript 内存；局部变量和 Skript 自身排除的临时变量不会因此持久化。

必须同时修改以下两处配置，并重启服务器；插件不自动改写 Skript 配置：

1. `plugins/SkriptExtra/config.yml` 中设 `features.variable-storage.enabled: true`（默认 `false`）。
2. 在 `plugins/Skript/config.sk` 的 `databases` 中选择 `type: extra-jdbc`，配置连接及变量匹配范围。例如只接管 `g_` 前缀：

```yaml
databases:
    global:
        type: extra-jdbc
        pattern: g_.*
        host: localhost
        port: 3306
        user: your_user
        password: your_password
        database: your_database
        table: skript_global
        monitor changes: false
        ssl mode: disable
    default:
        type: CSV
        pattern: .*
        file: ./plugins/Skript/variables.csv
        backup interval: 2 hours
        backups to keep: -1
```

数据库按**从上到下首次匹配**路由。要接管更多变量，应明确调整 pattern 和顺序；仅打开 Extra 的开关不会迁移变量。不要保留仍为 `MySQL`/`SQLite` 的旧配置再直接删除 SQLibrary，即使该条目被更早的 CSV 规则覆盖，Skript 启动时仍会尝试加载它。无用条目应设 `type: disabled`。

默认 `ssl mode: disable` 适合本机连接；远程数据库应配置 `verify-full` 并正确提供受信任证书。支持 `disable`、`trust`、`verify-ca`、`verify-full`。

Extra 的 `features.variable-storage` 参数：

| 参数 | 默认值 | 行为 |
| --- | --- | --- |
| `enabled` | `false` | 允许启用 `extra-jdbc` 后端；配置仍引用该类型时关闭开关会中止服务器启动，避免空加载。 |
| `warn-legacy-sqlibrary` | `true` | 独立检测原生 MySQL 配置并在控制台 WARN，提示迁移后再移除 SQLibrary；仅安装 SQLibrary 不触发 MySQL 慢存储告警。 |
| `batch-size` | `512` | 每次最多处理 1–4096 条日志；同批同变量只提交最终状态。 |
| `flush-interval-ms` | `25` | 空闲时的批次检查和恢复日志定期 fsync 间隔，允许 1–1000ms；不是从 Skript `set` 返回起计算的落盘承诺。 |
| `retry-delay-ms` | `250` | 临时故障初始等待，指数退避至最多约 5 秒；告警最多每 10 秒一次。 |
| `connect-timeout-ms` / `socket-timeout-ms` | `3000` / `5000` | 单次连接和 IO 超时，100–30000ms。 |
| `shutdown-timeout-ms` | `30000` | 关闭期间继续提交的期限；另允许一次在途连接/IO 完成。超时后保留日志供下次启动回放。 |
| `maximum-journal-mib` | `256` | 每张表的恢复日志磁盘上限；达到上限会请求停服，需处理数据库或磁盘故障。 |

### 数据与迁移

连接的数据库必须预先存在，账号需要建表（首次启动）、读取、插入、更新、删除及获取会话 advisory lock 的权限。新表使用 InnoDB、区分大小写的变量名、MEDIUMBLOB 值。也支持原生 SQLibrary 的 `name/type/value/update_guid`、唯一 name 索引、InnoDB 表，保留其原有字段及大小限制；不会自动 ALTER 旧表或改变字符集。原型或其他插件的表可能不兼容，不能直接复用。

迁移原生 MySQL 时先正常停服、备份数据库和 CSV，保持相同表和 pattern，改类型并关闭 `monitor changes`。此实现是**单服务器写入**，使用数据库会话锁拒绝另一个 addon 写入器；不支持原生监视、多服共享状态或外部写入。旧的 SQLibrary 写入器不会遵守此锁，必须先停止。跨存储重新分配仍由 Skript 执行，调整 pattern 前务必离线备份并核验数据。

没有可写连接、表结构不兼容、无法反序列化、Skript 私有加载接口变化或日志校验失败时，会请求停服并中止加载，不会自动退回 CSV。加载桥使用受控反射，升级 Skript 后必须先在隔离环境验证。

后台事务失败会回滚并保留批次；断线、锁等待等暂时故障重连后重试。权限、字段长度、表结构等永久错误会停止服务器，保留未提交日志。日志位于 `plugins/SkriptExtra/variable-journal/`，校验完整帧，修复崩溃留下的不完整尾帧；数据库提交成功后才回收。不要删除该目录或带着未提交日志切换连接地址，否则会失去恢复入口。

驱动使用独立类加载器，允许 Paper 先卸载 Extra，再由 Skript 在脚本卸载及保存队列排空后关闭数据库。必须完整重启，不支持热重载插件。

本地日志仅保护已经送达此后端的变更。Skript 的内存保存队列及尚未 fsync 的末尾仍存在崩溃/掉电窗口，普通 `set` 返回也不代表数据库已提交；磁盘已满时同样无法承诺新变更持久化。它不提供跨变量业务事务，也不适合作为交易流水。

## 语法与功能配置

支持 map 变量、嵌入 section 及多条件 `&&`/`||`。嵌入 section 需要 skript-reflect，并检测与 Vessel 的冲突。相关开关在 `features.map-variables`、`features.conditions` 及各事件/表达式配置中。

## PacketEvents 配置

首次启动生成 `plugins/SkriptExtra/config.yml`；已有配置保留并补齐缺失项。修改后重启服务器，避免热卸载已注册的 Skript 语法。

`features.packet-events.enabled` 控制集成总开关。下面四组各有 `enabled` 开关：

| 配置组 | 参数与行为 |
| --- | --- |
| `view-distance` | `value`：1–32，默认 32；修改客户端视距包，不扩大实际区块发送范围。 |
| `simulation-distance` | `value`：1–32，默认 32；修改客户端模拟距离包，不改变服务器模拟范围。 |
| `entity-status-override` | `permission` 默认 `sicilia.admin`，空串允许所有玩家；`status` 为 -128–127，默认 28。符合权限的接收者所收到的所有 entity status 包都会被覆盖。权限缓存每秒在服务器线程刷新。 |
| `biome-namespace-rewrite` | `registry`、`source-namespace`、`source-path-prefix`、`target-namespace`：默认把 `terra:sicilia/sicilia/<完整剩余路径>` 转为 `sicilia:<完整剩余路径>`。其他命名空间、不匹配路径和空后缀保持原样。重写产生重复 ID 时整包保持原样并警告。 |

默认启用四组功能。无效的范围或命名空间会禁用对应组并警告。四组全部关闭时不注册监听器；没有安装 PacketEvents 时跳过集成。API 初始化和终止始终由 PacketEvents 插件负责，Extra 只注册和注销自己的监听器。

构建与本服验证不代表对所有 Minecraft、Skript 或客户端版本完成全面验收。
