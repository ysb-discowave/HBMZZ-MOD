# 宣传片可用素材清单

> 给段渲染器（shot_01…shot_08）用。路径均相对 `src/main/resources/assets/`（用 `promo_kit.Frame.tex()` 传入即可，它会自动拼前缀）。
> 全部是**真实存在的游戏内贴图**，不是外部素材，可以放心用。
> 尺寸都是实测值。16×16 是方块贴图，放大时 `promo_kit` 用 NEAREST，保持像素锐利——这正是我们要的工业风。

---

## 1. 屏幕与方块（`hbmzz/textures/blocks/`，全部 16×16）

| 文件 | 用途建议 |
|---|---|
| `dfc_monitor_front.png` | **S03 主角**：DFC 监控屏正面 |
| `dfc_monitor_side.png` / `dfc_monitor_top.png` | S03 屏体侧面/顶面，做 2.5D 屏体 |
| `nuke_monitor_front.png` | S01/S07 六块屏之一；核事故监控屏 |
| `alert_display_front.png` | S07 警报显示牌 |
| `control_panel_front.png` | S04/S05 面板正面（另有同尺寸 128×128 大图见 §3） |
| `video_broadcaster_front.png` / `_on.png` | **S06-1 主角**：视频广播器，`_on` 是通电态 |
| `code_broadcaster_front.png` | S06-3 核事故代码广播器 |
| `nuke_controller_front.png` / `nuke_body.png` / `nuke_nose.png` / `nuke_stripe.png` | S06-3 核弹剪影可拼出弹体 |
| `nuke_lamp.png` | 告警灯 |
| `dfc_blast_sensor_front.png` / `_on.png` | 传感器，`_on` 为触发态 |
| `monitor_camera.png` / `_side` / `_top` | S06-2 监控摄像头 |
| `gate_and.png` / `gate_or.png` / `gate_not.png` / `gate_xor.png`（各带 `_on`） | 红石门，可做"逻辑"视觉符号 |
| `gate_side.png` | 门的侧边 |

## 2. 物品（`hbmzz/textures/items/`，全部 16×16）

| 文件 | 用途建议 |
|---|---|
| `hacker_tablet.png` | **S06-2 主角**：黑客平板 |
| `monitor_linker.png` | 屏幕连接器，S03 里可当"信号源"图标 |
| `monitor_camera.png` | 摄像头物品 |
| `gun_pistol.png` / `gun_rifle.png` / `gun_shotgun.png` | 制式枪械，S06 快剪可一闪 |
| `jetpack.png` / `jump_coil.png` / `speed_coil.png` / `regen_coil.png` | Roblox 线圈/喷气背包 |

## 3. 控件本体（`hbmzz/textures/models/control_panel/`）

**这是全片最好用的素材**——真实控件的 3D 贴图，尺寸够大，放大后依然干净。

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `control_panel_front.png` | 128×128 | **S04/S05 面板底板**，直接当面板正面 |
| `button_push.png` | 32×32 | S04 控件飞入 |
| `button_encased_push.png` | 64×64 | S04 控件飞入（更大更清晰） |
| `button_emergency_push.png` | 64×64 | S04/S06 急停按钮，红色系高光 |
| `dial_large.png` | 64×64 | S04 旋钮 |
| `dial_square.png` | 64×64 | S04 方形表盘 |
| `knob_control.png` | 64×64 | S04 旋钮 |
| `slider_vertical.png` | 64×64 | S04 推杆 |
| `switch_toggle.png` | 32×32 | S04 开关 |
| `switch_rotary_toggle.png` | 32×32 | S04 旋转开关 |
| `display_7seg.png` | 32×32 | **S03/S04 数码管**，可做读数 |
| `*_gui.png` | 16×16 或更小 | 同一批控件的 GUI 小图标，适合做"控件库"网格 |

## 4. 附属包控件（`hbmzz/textures/models/control_panel/addon/`）

用来体现"附属包（PolyForm Shield）也有自己的控件"。

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `meter_vertical_0.png` … `_3.png` / `_-1.png` … `_-3.png` | 19×51 | 指针表，七档，适合做"读数变化" |
| `nixie.png` | 18×25 | 辉光管数字 |
| `textindicator.png` | 45×19 | 文字指示条 |
| `textindicator_light.png` | 106×42 | 发光文字指示条 |
| `lever_vertical.png` / `lever_horizontal.png` | 20×40 / 40×20 | 拉杆 |
| `starboundbuttonlol.png` / `starboundswitchlol.png` | 20×20 | 方形按钮/开关 |

## 5. 面板 GUI（`hbmzz/textures/gui/control_panel/`）

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `grid.png` | 256×256 | **S04 网格底板**，控件吸附的目标 |
| `gui_base.png` | 256×256 | 面板编辑器窗体底板 |
| `node.png` | 128×128 | **S05 节点图节点图标** |
| `placement_grid.png` | 64×64 | 放置网格 |
| `resize_grids.png` / `gui_resize_grid.png` | 256×256 | 缩放把手 |
| `white.png` | 16×16 | 纯白，可当遮罩/高光 |
| `solid.png` / `solid_emissive.png` | 1×1 | 纯色/自发光色块，可做色块填充 |

## 6. Redstone Audio 方块（`rsaudio/textures/blocks/`，全部 16×16）

**S01 六块屏"各说各话"的配色来源**——这里有一整套彩色告警灯。

| 文件 | 用途 |
|---|---|
| `alarm_red_off/_on.png`、`alarm_yellow_*`、`alarm_orange_*`、`alarm_blue_*`、`alarm_green_*`、`alarm_purple_*` | S01 六块屏各自的告警灯；S06-3 核事故八级可用红/橙/黄递进 |
| `door_front.png` / `_open.png`、`door_panel*.png` | 气闸门，S06 快剪 |
| `elevator_*`（客梯/货梯/斜梯控制器 + 门 + 井道） | 电梯，S06 快闪 |
| `train_body_*` / `train_rail*` / `train_station_*` / `train_bogie.png` | 列车，横向运动镜头可用 |
| `tartarus_*`（hatch / hazard / hub_* / rim / underside） | **塔塔鲁斯大门**，S06 或 S08 前的高潮画面 |
| `monitor_camera_body/lens/mount.png` | 摄像头分解视图，S06-2 可用 |
| `monitor_console_*.png` | 监控台，S03 可做"操作席" |
| `giant_pipe.png` / `giant_sign.png` / `giant_sign_lit.png` | 管道与巨幅标牌，做场景纵深 |
| `announcer_front(_on).png` | 广播器，呼应 S06-1 |
| `audio_player_front(_on).png` | 音频播放器，呼应 OST |
| `dmr_*` / `f9_*` | 大型机器（DMR / F9），做重型机械背景 |
| `crane_base_front.png` / `exhaust_fan_*.png` / `loader_*.png` | 吊车底座、排风扇、装载机，S02/S05 背景元素 |

## 7. 品牌与主视觉（只读，不改）

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `docs/assets/promo/poster.jpg` | 1600×900 | **S02 主视觉**：控制面板 + 监控屏 3D 渲染，含 HBMZZ 大字与标语 |
| `docs/assets/brand/logo-mark.svg` / `logo-mark-512.png` | 512×512 | 主标，S08 定格 |
| `docs/assets/brand/logo-lockup.svg` | 560×128 | 横排版（标 + HBMZZ 字 + 琥珀下划线） |
| `docs/assets/brand/logo-mark-light-512.png` | 512×512 | 浅色版，必要时用 |
| `docs/assets/brand/ost-cover-1024.png` | 1024×1024 | OST 封面，S08 提音乐时可一闪 |

> 用 `promo_kit.Frame.poster()` 读 poster.jpg；`promo_kit` 另提供 `brand()` 可读 `docs/assets/brand/` 下的 PNG（若无此函数，用 PIL 直接 `Image.open` 绝对路径打开即可）。

---

## 用素材的三条原则

1. **像素贴图不模糊**：16×16 放大到 200px 以上时，一定是 NEAREST。糊了就不是这个片的质感。
2. **别堆料**：一帧里最多 2–3 种贴图。工业风靠留白和线框，不靠塞满。
3. **贴图只当"实物"，不当装饰**。它们代表真实存在的方块/控件，出现就要有理由——要么在讲它的功能，要么在指它属于哪一类。

## 明确不要用的东西

- 不要用 `rsaudio` 的紫色/绿色告警灯去表达"正常/异常"语义（本片只用品牌四色表达状态）。
- 不要把 `invisible.png`（1×1 透明）、`solid.png`（1×1）当图片素材，它们只是纯色填充。
- 不要引用 `docs/assets/brand/` 以外的任何外部图片。
