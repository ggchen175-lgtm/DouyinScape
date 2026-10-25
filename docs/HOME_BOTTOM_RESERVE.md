# 首页底部安全区：按缺口补偿

## 问题

`HomeTabSafeArea` 原来用 `hasVisibleFeedSpace()` 得到一个布尔值 `feed`，命中就给 `host.parent` 加满一个 `nav`，没命中就一点都不加。

这在不同机型上会走向两个极端：

- 页面自己已经把底部控件抬起来了 → 再加一个 `nav`，变成**重复抬高**，进度条离小白条越来越远；
- 页面没有 `BottomSpace`（容器改名或换布局）→ 一点不加，**进度条留在手势区里**。

两者都不是"按机型写死像素"能修的，因为抬不抬、抬多少本来就不是常量。

## 现在的规则

不再问"是不是 feed 页"，而是问"页面自己还差多少"：

```
safeBottom = 屏幕底 - nav
nativeBottom = 锚点当前底边 + 本模块已加的 padding   // 还原页面自己的位置
reserve = clamp(nativeBottom - safeBottom, 0, nav)
parent.paddingBottom = 页面原有 padding + reserve
```

三个分支自然成立，不需要判断机型：

| 页面自身状态 | nativeBottom | reserve | 结果 |
| --- | --- | --- | --- |
| 完全没让出 | 屏幕底 | nav | 抬一个完整 inset（与旧行为一致） |
| 已经让出 | 安全底边 | 0 | 不动，避免重复抬高 |
| 让出一部分 | 中间 | 差值 | 只补缺口 |

`+ ourExtra` 这一步是关键：模块自己加的 padding 会把内容顶上去，测量时必须减掉，否则下一帧会把"自己造成的位移"当成页面的原生位置，来回抖动。

## 锚点

优先用原生的 `com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace`：它的底边就是标签栏顶边，直接反映页面给底部留了多少。

找不到时回退到"宽度 ≥ 屏幕 80%、类名含 SeekBar、底边最低"的那个容器（实测是 `DProSeekBar`，`ProSeekBarView` 那条细条会因为底边更高而被排除）。这样容器改名时仍然有锚点，而不是静默退出。

两者都没有（消息页、个人页）时 `reserve = 0`，保持原样。

## 触摸区域

位置只由 `parent.paddingBottom` 和标签栏 `translationY` 两处写入，没有第三处。`translationY` 本来就是按 `safeBottom` 反算的，页面自己已经抬够时它算出来就是 0，所以触摸和视觉用的是同一个值，不会出现"看得到点不到"。

## 标签容器高度与内容预留是两件事

第一版改动把 `host.height` 也绑到了 `reserve` 上，结果消息页标签栏被裁掉半截：

- 消息页没有 feed 锚点 → `reserve = 0` → 容器高度停在 129；
- 但标签行仍需要按安全底边上移 63，于是行顶超出容器顶 62px，被 `clipChildren` 裁掉。

实测（1080×2400，nav=63）：

| 页面 | 容器 `MainBottomTabContainer` | 标签行 `root_view` |
| --- | --- | --- |
| 消息页（错误版） | 2271，高 129 | 2209，高 128，`translationY=-63` → 顶出容器 |
| 消息页（修正后） | 2208，高 192 | 2209，高 128，`translationY=0` |
| 首页（修正后） | 2208，高 192 | 2209，高 128，`translationY=0` |

所以分成两个量：

- **容器高度**：始终 `原生高度 + nav`，只负责"装得下被抬起的标签行"，和页面有没有预留无关；
- **父容器 padding**：`原生 padding + reserve`，按实测缺口；
- **容器外边距**：`原生外边距 - (原生 padding + reserve)`，把父容器新增的 padding 抵掉，让容器底边始终贴着屏幕底。

三者一起才保证：背景铺到屏幕底、标签行停在安全底边、且行不会被容器裁掉。

## 验证

Android 17 模拟器（1080×2400，手势导航，nav=63）。

**A/B：模块开关**

- 停用模块：应用窗口本身只有 2337 高，`navigationBarBackground` 占 2337–2400，页面自身没有底部 padding；
- 启用模块：窗口铺到 2400，`rp5` 底 padding=63，`BottomSpace` 与 `DProSeekBar` 底边都是 2337。

**A/B：页面是否已经自己让出**

抖音 targetSdk 28，系统不会强制它边到边，正常跑只能得到"没让出"这一种状态。所以用一次性测试桩在 `bind()` 之前给 `host.parent` 预置一个 `nav` 的 padding，模拟"ROM/应用自己已经让出"，实测：

| 场景 | 日志 |
| --- | --- |
| 页面已让出 63 | `nav=63 native=63 reserve=0 applied=63 anchor=BottomSpace@2337` |
| 页面没让出 | `native=0`，模块补 `reserve=63`，最终 `applied=63` |

第一种情况下模块加的是 0，总 padding 仍是 63；改动前的写法是 `parentPadding + nav`，同样输入下会变成 126，也就是重复抬高。测试桩已从源码移除，仅保留结论。

需要真机复核的只剩一点：那台机器的 `BottomSpace` 底边是否已经等于 `屏幕高 - nav`。是则 `reserve` 应为 0、`rp5` 的 padding 不再增加。
