# SkriptExtra

Skript 扩展。Java 21+，需要 Skript；WorldEdit、PlaceholderAPI、PacketEvents 为可选插件。

## 构建

```sh
./gradlew build
```

输出 `build/libs/SkriptExtra-1.0.5.jar`。版本只在 `build.gradle.kts` 定义，构建时写入 `plugin.yml`，日志读取该元数据；Bukkit 自行添加 `[SkriptExtra]` 前缀。所有依赖都是 `compileOnly`，使用普通 JAR，无需 Shadow。

也可以使用服务器已安装的依赖进行离线兼容构建：

```sh
./gradlew --offline -PlocalServerDir=/absolute/server/path build
```

此模式用于验证指定服务器的 API，不能据此推断其他版本兼容性。

## PacketEvents 配置

首次启动生成 `plugins/SkriptExtra/config.yml`；已有配置保留并补齐缺失项。修改后重启服务器，避免热卸载已注册的 Skript 语法。

`features.packet-events.enabled` 控制集成总开关。下面四组各有 `enabled` 开关：

| 配置组 | 参数与行为 |
| --- | --- |
| `view-distance` | `value`：1–32，默认 32；修改客户端视距包，不扩大实际区块发送范围。 |
| `simulation-distance` | `value`：1–32，默认 32；修改客户端模拟距离包，不改变服务器模拟范围。 |
| `entity-status-override` | `permission` 默认 `sicilia.admin`，空串允许所有玩家；`status` 为 -128–127，默认 28。符合权限的接收者所收到的所有 entity status 包都会被覆盖。权限缓存每秒在服务器线程刷新。 |
| `biome-namespace-rewrite` | `registry`、`source-namespace`、`source-path-prefix`、`target-namespace`：默认把 `terra:sicilia/sicilia/<完整剩余路径>` 转为 `sicilia:<完整剩余路径>`。其他命名空间、不匹配路径和空后缀保持原样。重写产生重复 ID 时整包保持原样并警告。 |

默认启用四组功能，以保留既有行为。无效的范围或命名空间会禁用对应组并警告。四组全部关闭时不注册监听器；没有安装 PacketEvents 时跳过集成。API 初始化和终止始终由 PacketEvents 插件负责，Extra 只注册和注销自己的监听器。

## 1.0.5 修复

- 移除重复日志前缀；使用插件元数据版本，消除 `@VERSION@`。
- 添加并接入 PE 总开关、分项开关与参数；避免缺少可选依赖时启动失败、Extra 卸载时终止共享 API。
- 群系路径不再使用固定数组下标，保留嵌套路径，并检查目标 ID 冲突。
- 网络包线程不读取 Bukkit 配置或玩家权限。
- 补充 PlaceholderAPI 加载顺序与普通 WorldEdit 受检异常兼容。

本服原有 JAR 包含仓库之外的 map/multiple-condition 等扩展；本项目构建不会自动合并这些本地功能。部署前应单独对齐，避免覆盖功能。本次构建验证不代表对所有旧语法和所有客户端完成全面验收。
