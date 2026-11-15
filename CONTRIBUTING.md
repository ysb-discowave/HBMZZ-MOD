# 参与 HBMZZ 开发

这是一个 Minecraft 1.12.2 Forge 附属模组，GPL-3.0-only。下面是一份「从零到能提 PR」的
最短路径，以及几条不能破的规矩。碰代码之前请先读完**红线**那一节。

---

## 1. 环境

| 需要 | 版本 | 说明 |
|---|---|---|
| JDK | **8**（必须） | 1.12.2 的 ForgeGradle 只在 8 上工作，17/21 会直接失败 |
| Gradle | **4.10.3** | 不要用系统的 `gradle` 命令，用下面的方式直接喂 launcher jar |
| Python | 3.8+ | 所有离线检查都是 Python 或由 Python 调起 |
| bash | Git Bash（Windows） | 五个 `tools/*/run.sh` 是 bash 脚本 |

`JAVA_HOME` 指向 JDK 8 的根目录（`.../jdk8u512-b09` 这种，不是 `bin`）。

### 构建

```bash
# Windows / Git Bash：./gradlew 在这个仓库里是坏的（见下），直接喂 launcher
"$JAVA_HOME/bin/java" -Xmx3G \
  -classpath "$GRADLE_HOME/lib/gradle-launcher-4.10.3.jar" \
  org.gradle.launcher.GradleMain build

# Linux / macOS 同样，只是路径换成正斜杠
```

产物：`build/libs/hbmzz-<version>.jar`。**首次构建 15–20 分钟**，大头是 ForgeGradle 的
反编译与 `sourceMainJava`，不是 javac。之后增量构建快得多。

两个会让人误判「网断了」的坑：

- `~/.gradle/gradle.properties` 里如果有 `systemProp.http.proxyHost` 之类的残留，
  Gradle 每个请求都会 `Connection timed out`，而 `curl` 是通的。删掉那几行。
- `./gradlew` 传给 java 的 `-classpath` 是 POSIX 路径，Windows 的 java 报
  `Could not find or load main class ...GradleWrapperMain`。这就是上面为什么要直接喂 jar。

**`build/sources/main/java` 是快照**：构建跑起来之后再改 `src/` 里的源码不会进这一轮
产物，必须重跑一次。看到 `BUILD SUCCESSFUL` 却「改动没生效」，多半是这个。

---

## 2. 提交之前先自检

```bash
python tools/checkall.py          # 有构建产物就跑两档，没有就只跑快速档
python tools/checkall.py --fast    # 几秒：注册表、站点审计、站点数据一致性
python tools/checkall.py --full    # 构建档缺失算失败，发版前跑这个
```

分档的原因是成本差两个数量级：快速档纯静态解析，构建档要真的编译并运行实现类
（`rbmkcheck` / `cecpcheck` / `termcheck` / `videocheck` / `linkercheck` / `reasimcheck`）。
PR 的 CI 只跑快速档；构建档在本地或 self-hosted runner 上跑。

`tools/quickcheck.py` 是 40 秒的 javac 体检，可以当快速反馈，但它的 Forge jar 是 SRG 名，
`getFront` / `mcServer` 这类会报假阳性——**过没过以 `checkall` 与 `gradle build` 为准**。

改站点还要：

```bash
python tools/site/sync_site.py     # 重生成 data.js 并刷新页面里的资源指纹
python tools/site/serve.py 8766    # 本地预览（必须用它，理由见下）
```

`python -m http.server` 不支持 Range，`<audio>.seekable` 只有 `[0,0]`，播放器的进度
与跨页续播会看起来坏掉。`serve.py` 实现了 Range。

---

## 3. 分支与 PR

- `master` 是发布分支，**受保护，不能直推**。
- 从 `master` 开短分支：`feat/dfc-sensor`、`fix/panel-tilt`、`docs/ost-page`。
- 提 PR，等 CI 绿，等 review，**squash 合并**。
- 只有一个维护者能打 tag 和发 Release。

PR 里请写清三件事：**为什么改**（原来怎样、现在怎样）、**怎么验的**（`checkall` 输出、
进游戏的实际操作与截图）、**许可声明**（自己写的，没有从非 GPL 来源抄）。

提交信息用中文，说清「原来怎样 / 现在怎样」，不要写「优化了体验」。历史提交可以当范本。

如果你想长期参与但没有写权限：fork → 开分支 → PR，一样走这套流程。

---

## 4. 改哪些文件容易撞车

这五个地方所有人都会碰，多人并行时最容易冲突：

| 文件 | 约定 |
|---|---|
| `ModContent.java` | 新方块/物品**加在区块末尾**，不要插在中间重排 |
| `HBMZZ.java` | 注册各段独立成块，一个功能一块 |
| `HBMZZConfig.java` | 每个功能一节（`[nuke]` / `[alert]` / `[dfcSensor]`…），只追加 |
| `ClientProxy.java` | 三张物品模型表**必须等长**（regcheck 第 62 条盯着） |
| `assets/hbmzz/lang/*.lang` | 只追加到文件末尾，不重排、不改顺序 |

新增方块/物品的完整清单在 `README` 的「加东西的清单」一节——漏一步编译不报错，
进游戏才静默失效。`regcheck` 会在离线把这些兜住。

---

## 5. 红线

1. **每个 `.java` 都要带 GPL 头**，照抄现有文件的头。
2. **不许从非 GPL 模组抄代码。** NTM 生态里来源不清的代码很多，混进来一次就得整包重写。
   参考思路可以，抄实现不行。
3. **`AUTHORIZATION.md` 不能删。** 监控屏的 TESR、吊装几何、画布管线来自节枝 JiezhiMod，
   作者给了书面授权，那 8 个文件的头里多一行说明。
4. **`com.rsaudio`（Redstone Audio）是作者自制**，不是第三方来源。写文档、changelog、
   Release 时不许含糊成「并了第三方模组」。
5. 提交带 `Signed-off-by`（`git commit -s`），表示你有权按 GPL-3.0 提交这段代码。

---

## 6. 发版（只有维护者做）

```bash
# 1) 三处版本号
build.gradle                     version = 'x.y.z'
src/main/resources/mcmod.info    "version": "x.y.z"
src/main/java/com/hbmzz/HBMZZ.java   public static final String VERSION = "x.y.z";

# 2) 站点：changelog.html 与 tools/site/content.json 的 timeline 成对加条目
python tools/site/sync_site.py && python tools/site/sync_site.py --check

# 3) 构建与自检
gradle build && python tools/checkall.py --full

# 4) 提交、打 tag、推、建 Release（jar + sources 两个资产）
```

站点上的版本号由 `sync_site.py` 从 `build.gradle` 带出，不要手写。`--check` 会比对
`content.json` 的每个版本在 `changelog.html` 里都有 `v<版本>`，缺一个就 FAIL。
