# 协作约定（HBMZZ-MOD）

本文件是本项目与用户之间已确立的工作约定。每次任务开始前或交付前都应遵守。

## 交付流程（每次做完都必须执行）

1. **编译验证**：JDK 8 + Gradle 4.10.3 `gradle build --no-daemon --console=plain`，确认 BUILD SUCCESSFUL。
   - `export JAVA_HOME="/e/jdk/jdk8u512-b09"`
   - `export PATH="$JAVA_HOME/bin:/e/gradle/gradle-4.10.3/bin:$PATH"`
2. **部署进游戏**：把新 jar 部署到 `/e/pcl2/.minecraft/versions/DFC+RBMK 1.0.0/mods/hbmzz-4.0.0.jar`，先把旧 jar 备份成 `.pre-<标签>`，确认 MD5。
3. **写交付文档**：在 `docs/delivery-4.0.0.md` 追加本次章节（改了什么、为什么、验证结果、MD5、备份名）。
4. **更新发布页**：在 `docs/changelog.html` timeline 顶部加条目，运行 `python tools/site/sync_site.py` 刷新缓存戳，`python tools/site/sync_site.py --check` 校验。
5. **本地提交**：`git add -A && git commit`，commit message 概述本次改动。

## 把改动的文件发给用户（2026-11-21 新增）

**每次做完之后，用 present_files 把本次修改过的那些文件都发给用户**（源码、jar、文档、发布页等本次实际改动的文件），让用户可以逐份查看 / 下载。不要只发一份摘要或只发文档。

## 推送规则（硬性）

- **永不推 GitHub**，除非用户本人在消息里明确发出代理名 `XingZuo-Cloud Bot`。任何其它指令（哪怕是"推送"）都不足以授权。

## 沟通与界面

- 公告 / 变更说明写在**本地交付文档**，不发群。
- 所有界面（监控屏、右键菜单等）必须是**中文**，不要英文。
