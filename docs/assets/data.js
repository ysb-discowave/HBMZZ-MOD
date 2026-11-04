/* 本文件由 tools/site/sync_site.py 自动生成，请勿手工编辑。
 * 改内容去 tools/site/content.json；改版本号去 build.gradle。
 */
window.HBMZZ_DATA = {
  "site": {
    "name": "HBMZZ",
    "fullName": "HBMZZ — DFC 监控屏 / 机器控制面板 / 制式枪械",
    "tagline": "给 HBM 的核科技装一块真的显示器",
    "description": "面向 HBM 的核科技社区版（NTM:CE）及 Leafia 诅咒版附属的 Minecraft 1.12.2 Forge 模组：DFC 实时监控屏、可自由编排的机器控制面板、移植自诅咒版的节点图控制台，外加制式枪械与线圈/喷气背包。",
    "repo": "https://github.com/ysb-discowave/HBMZZ-MOD",
    "site": "https://ysb-discowave.github.io/HBMZZ-MOD/",
    "license": "GPL-3.0-only",
    "author": "Yang Sibo",
    "authorHandle": "ysb-discowave"
  },
  "downloads": [
    {
      "label": "下载最新版",
      "href": "https://github.com/ysb-discowave/HBMZZ-MOD/releases/latest",
      "kind": "primary",
      "note": "GitHub Releases"
    },
    {
      "label": "查看源码",
      "href": "https://github.com/ysb-discowave/HBMZZ-MOD",
      "kind": "ghost",
      "note": "仓库"
    },
    {
      "label": "使用指南",
      "href": "guide.html",
      "kind": "ghost",
      "note": "从安装开始"
    }
  ],
  "nav": [
    {
      "label": "首页",
      "href": "index.html"
    },
    {
      "label": "使用指南",
      "href": "guide.html"
    },
    {
      "label": "参考手册",
      "href": "reference.html"
    },
    {
      "label": "更新日志",
      "href": "changelog.html"
    },
    {
      "label": "许可",
      "href": "license.html"
    }
  ],
  "pillars": [
    {
      "num": "01",
      "title": "实时屏幕",
      "summary": "方块正面不是贴图，是一台真在绘制的显示器。AWT 离屏画布 + 3 倍超采样，21px 读数、真圆环刻度、抗锯齿曲线同时出现在同一块面板上。",
      "points": [
        "AHBSSMFI-OS 开机自检",
        "双环量表 + 七路结构灯柱",
        "1–4 格宽 × 1–3 格高不变形",
        "坍缩时自动切 ERROR 301"
      ],
      "href": "guide.html#screen"
    },
    {
      "num": "02",
      "title": "可缩放终端",
      "summary": "右键打开终端，左列是控制、右半边是这块屏的实时预览——预览里的像素和你站在方块前看到的是同一份。",
      "points": [
        "拖角 / 滚轮 / 双击最大化",
        "服务端权威，多人同时看不打架",
        "功率计绑定与融毁倒计时",
        "尺寸存进方块 NBT"
      ],
      "href": "guide.html#terminal"
    },
    {
      "num": "03",
      "title": "面板工坊",
      "summary": "第四页 04 自定义 留给你自己。控制件怎么摆、什么时候报警，全部用「当 + 若 + 则」的积木规则描述。",
      "points": [
        "7 种可添加控件",
        "条件与控件共用数据通道",
        "独立编辑窗口，保存才写世界",
        "预设库复用"
      ],
      "href": "guide.html#workshop"
    },
    {
      "num": "04",
      "title": "机器控制面板",
      "summary": "用数据线右键机器记录、再右键面板，在 A–F 六个槽位里绑定，然后拧旋钮就能真的操控 NTM 机器。",
      "points": [
        "13 种控件",
        "反射发送上游 ControlEvent",
        "跨模组适配，含多方块核心",
        "规则引擎服务端执行"
      ],
      "href": "guide.html#control-panel"
    },
    {
      "num": "05",
      "title": "节点图控制台",
      "summary": "从诅咒版（Leafia CE Addon）移植的整套控制台：25 种控件、事件表、节点图编辑器，编译成可执行节点网络。",
      "points": [
        "25 种控件 + 11 种附属控件",
        "运行时镜像上游事件表",
        "节点图拖拽编辑",
        "结构与逻辑分离"
      ],
      "href": "guide.html#console"
    },
    {
      "num": "06",
      "title": "枪械 / 线圈 / 喷气背包",
      "summary": "顺带做的：制式枪械、Roblox 风格的加特林线圈与喷气背包。它们和监控屏共用同一套资源与配方体系。",
      "points": [
        "制式枪械与弹道",
        "线圈：蓄力与过载",
        "喷气背包：耐久即燃料",
        "完整配方"
      ],
      "href": "guide.html#extras"
    }
  ],
  "quickstart": [
    {
      "title": "装好前置",
      "body": "Minecraft 1.12.2 + Forge 14.23.5.2847 以上。想读 DFC 数据就装 NTM:CE（2.6.1.0 测试通过）；用诅咒版的客户端另需 MixinBooter —— 本模组自身不使用 Mixin。"
    },
    {
      "title": "丢进 mods",
      "body": "把 hbmzz-<版本>.jar 放进 .minecraft/mods。本模组对 NTM 是软依赖（after:hbm），没有 NTM 也能启动，只是屏上会显示「未找到堆芯」。"
    },
    {
      "title": "放下监控屏",
      "body": "正面会自动开机并显示读数。16 格内有 DFC 核心就会自动绑定；想固定核心或读功率计，用手持的监控数据线右键核心 / 红色电缆仪表，再右键监控屏。"
    },
    {
      "title": "打开终端调屏",
      "body": "右键监控屏打开终端：开关电源、切四个视图、改墙屏跨几格、拖窗口大小。想看板子之外的机器，再放一块机器控制面板。"
    }
  ],
  "compat": {
    "tested": [
      {
        "k": "Minecraft",
        "v": "1.12.2"
      },
      {
        "k": "Forge",
        "v": "14.23.5.2847+"
      },
      {
        "k": "NTM:CE",
        "v": "2.6.1.0 测试通过（软依赖）"
      },
      {
        "k": "Leafia's Cursed Addon",
        "v": "可选；DFC 重做版提供完整遥测"
      },
      {
        "k": "MixinBooter",
        "v": "诅咒版附属需要；本模组不使用 Mixin"
      }
    ]
  },
  "footerLinks": [
    {
      "title": "文档",
      "links": [
        {
          "label": "使用指南",
          "href": "guide.html"
        },
        {
          "label": "参考手册",
          "href": "reference.html"
        },
        {
          "label": "更新日志",
          "href": "changelog.html"
        },
        {
          "label": "配置项",
          "href": "reference.html#config"
        }
      ]
    },
    {
      "title": "项目",
      "links": [
        {
          "label": "GitHub 仓库",
          "href": "https://github.com/ysb-discowave/HBMZZ-MOD"
        },
        {
          "label": "Releases",
          "href": "https://github.com/ysb-discowave/HBMZZ-MOD/releases"
        },
        {
          "label": "问题反馈",
          "href": "https://github.com/ysb-discowave/HBMZZ-MOD/issues"
        },
        {
          "label": "从源码构建",
          "href": "reference.html#build"
        }
      ]
    },
    {
      "title": "许可与致谢",
      "links": [
        {
          "label": "GPL-3.0 全文",
          "href": "license.html"
        },
        {
          "label": "第三方来源",
          "href": "license.html#notice"
        },
        {
          "label": "节枝授权",
          "href": "license.html#authorization"
        },
        {
          "label": "署名",
          "href": "license.html#credits"
        }
      ]
    }
  ],
  "timeline": [
    {
      "version": "2.3.0",
      "title": "视频广播器",
      "items": [
        "新增视频广播器方块：红石上升沿触发，把选中的玩家屏幕整个换成一段视频，播完自动恢复。",
        "红石按 tick 轮询判定，只认上升沿；进存档 / 区块重载后的第一个 tick 不触发，高频时钟走冷却。",
        "目标玩家用原版 EntitySelector 解析（@a / @p / @r / 带方括号参数），方块扮成命令等级 2 的发送者。",
        "视频是 config/hbmzz/videos/ 里的帧序列包（zip 或目录 + meta.json + 可选 audio.wav），用 JRE 自带的 ImageIO 解码，不带任何原生库。",
        "解码在后台线程，只保留固定帧数的窗口；播放按时钟走，跟不上就丢帧而不是拖慢声音。",
        "客户端缺文件时显示缺的文件名与路径，不是一块沉默的黑屏。",
        "新增 tools/video/pack.py（mp4 或现成图片 → 包）与 tools/videocheck（离线验证包能被读出来）。"
      ]
    },
    {
      "version": "2.2.1",
      "title": "带 OBJ 模型的附属控件",
      "items": [
        "移植 11 个带 OBJ 模型的诅咒版附属控件：Nixie 管显示、大方钮、大开关、横/竖拨杆、横/竖量表、RBMK 显示、图表、文字指示、绘图板。",
        "新增两个图表节点（追加数据点、取区间极值），图表控件因此可以自行累积历史。",
        "控件注册名统一加 leafia_ 前缀，避免与本体控件撞名。"
      ]
    },
    {
      "version": "2.2.0",
      "title": "补齐附属节点",
      "items": [
        "移植 14 个 Leafia 附属节点：播放声音、脉冲、置位/复位锁存、缓存、时钟、随机数（整型/小数）、归约、字符串转数、字符串拼接、子串、格式化、批量查询、SIPfx。",
        "播放声音改按「类名 + 字段名」反射解析上游音效表，不再硬编码。",
        "批量查询改走跨模组机器桥，原来只认诅咒版自己的接口。"
      ]
    },
    {
      "version": "2.1.9",
      "title": "事件真的能投到机器上",
      "items": [
        "修正事件广播的投递路径：控制台在只声明 tick 的情况下，广播事件原本会被直接丢弃。",
        "两个广播重载收敛到同一条投递链路，先查本地事件表，再查跨模组机器表。",
        "广播节点改用上游的事件副本，不再走会抛异常的新建路径。"
      ]
    },
    {
      "version": "2.1.8",
      "title": "事件表运行时镜像",
      "items": [
        "不再抄写固定的事件名单，改为运行时读取上游注册表，附属模组追加的事件同样能识别。",
        "广播与输出类节点的参数恢复完整：数值、取消两个都在，读取侧同样可读。"
      ]
    },
    {
      "version": "2.1.7",
      "title": "绘制批次修复",
      "items": [
        "带模型的控件改为追加进外层立即模式批次，不再自行 begin/draw 打断父批次。",
        "修复了控件一多就整片消失的渲染问题。"
      ]
    },
    {
      "version": "2.1.6",
      "title": "节点图里的中文",
      "items": [
        "诅咒版画节点图前会关掉 unicode 字模（它图里全是英文），中文落进去会被按 ch % 256 砍成 ASCII，于是节点标题、下拉项、参数标签全变成方块和问号，宽度也算错。",
        "节点图整段保持 unicode 字模开启；所有小字号标签改成按实测宽度裁剪，不再硬截 5 个字符。"
      ]
    },
    {
      "version": "2.1.5",
      "title": "把机器接进面板",
      "items": [
        "新增跨模组机器桥：CE 机器（含多方块核心）可被面板识别并绑定。",
        "A–F 槽位绑定流程定型，沿用至今。"
      ]
    }
  ],
  "roadmap": [
    {
      "state": "doing",
      "text": "官网与文档随版本自动同步（本页数据来自仓库扫描）"
    },
    {
      "state": "todo",
      "text": "补全带 GUI 的控件与节点的进游戏验证"
    },
    {
      "state": "todo",
      "text": "英文界面选项"
    },
    {
      "state": "done",
      "text": "GPL-3.0 开源发布所需的许可链（含节枝书面授权）"
    }
  ],
  "auto": {
    "version": "2.3.0",
    "mcVersion": "1.12.2",
    "forgeVersion": "14.23.5.2847",
    "stats": {
      "javaFiles": 237,
      "javaLines": 44142,
      "resourceFiles": 132,
      "nodeTypes": 32,
      "controlTypes": 25
    },
    "generatedAt": "2026-11-04"
  }
};
