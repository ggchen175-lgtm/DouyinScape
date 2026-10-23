# 抖音沉浸布局代码检查与整理（0.3.14）

范围：当前手机使用的 cn.zhe.douyinunified；检查其全部 Kotlin 源码中的布局写入，以及测量 Hook、Activity/Fragment 生命周期、主题与导航模块的调用关系。独立会话堆叠模块不在本次改动范围。

## 本次发现和处理

1. 聊天输入区：旧 syncChatDock 先写固定 -56px，随后 pinChatDockBottom 再写 overshoot 修正，两个入口操作同一 wrapper。改为 ChatDockGeometry 在一次计算中合成安全区和动画落点修正，再写入一次最终 translationY。原生键盘/附件面板状态机继续保留，避免收键盘途中误判面板。
2. 聊天列表：旧测量 Hook 会在首次测量时自行创建默认 56px 预留，即使导航侧还没绑定该列表。现在只测量已由导航控制器登记的列表，预留量直接读取输入区控制器的 reserve；不再独立猜测数值。
3. 回到最新消息按钮：旧 ChatScrollControlFix 同时负责主题颜色和位置。位置及其退出恢复迁到导航侧 ChatFloatingGeometry；主题侧只换颜色/资源。关闭沉浸功能时，主题代码不再顺带移动按钮。
4. 废弃代码：删除旧视频目标的四个缓存 ID、originalVideoY、lastVideoSearchAt、两个 strip 坐标缓存、两个 message 坐标缓存、stripChip、suggestionNeedsSearch，共 12 个废弃状态字段及相应只写入不读取的赋值。删除已无调用的两个直播 37px 常量。
5. 旧评论 IME 适配仍保留，但其导航补偿改为读取 WindowInsets，不再借用名为 CHAT_LIFT_PX 的聊天常量。剩余 56px 常量改名 NAV_FALLBACK_PX，只用于旧分支缺失 insets 时的回退，不作为当前聊天页固定抬高值。

## 布局职责清单

| 区域 | 唯一位置/尺寸负责人 | 决策与保留原因 |
|---|---|---|
| 系统栏透明与全屏窗口 | DouyinImmersiveNavModule 窗口 Hook | 仅处理窗口标志/颜色，不能代替内容安全区 |
| 首页/消息页底部标签 | HomeTabSafeArea | 消息页按原生增高底栏预留；首页有 BottomSpace 才补视口空间；维持 0.3.13 修复 |
| 私聊/群聊输入区 wrapper | ChatDockGeometry | 原生整组移动，键盘/附件/禁发状态决定 reserve，最终边界修正在同次计算完成 |
| 私聊/群聊消息列表 | ChatListInsets 测量 Hook | 使用 ChatDockGeometry 的 reserve；不是第二个独立抬高策略 |
| 聊天“回到最新”浮动按钮 | ChatFloatingGeometry | 根据实际输入区顶边和祖先裁剪边界避让，保留原生间距 |
| 聊天快捷表情栏 | 导航模块 pinChatStrip | 在它自己的原生父容器内居中；收键盘后的透明度/上抬只作用于快捷栏，不抬高输入区 |
| 聊天候选面板 | 导航模块 syncChatSuggestion | 将候选行底边对齐已定位的输入区顶边；移动整个小面板，避免只移动 RecyclerView 被裁剪 |
| 新版 Compose 评论区 | PageNavigationSafeArea | 只补剩余导航重叠；键盘显示时交回原生布局 |
| 旧版原生评论区/IME | 导航模块 CommentInputState | 受 mxe/e1q/e4c/csy 完整父子链约束；新版 Compose 不命中；保留旧布局兼容路径 |
| 公共视频详情（搜索、历史等） | PublicVideoSafeArea | 按编辑框、底栏、RTViewPager 和 BottomSpace 的共享布局关系识别；公共根观察器持有唯一实例 |
| 私信视频详情旧布局 | VideoBottomOverlays | 公共视频控制器命中时立即退出本分支；旧私信详情仍有独立作者信息、搜索栏和进度条，不能当作聊天输入区处理 |
| 私信详情图文与媒体遮挡 | VideoPhotoInsets + VideoMediaSafety | 媒体补偿显式扣减 photoReserve，避免重复预留；不是同一目标的两个独立导航 padding |
| 玩法挑战、音乐详情、搜索图文详情 | PageNavigationSafeArea 各结构策略 | 容器职责不同：列表尾部空间、浮动底栏、状态栏背景、Flow 输入宿主分别处理；不强行套统一 translation |
| 听抖音 | DecorNavigationInsetTransfer | 将 decor 已消费的导航占位转移到内容宿主，延伸背景，不移动播放按钮 |
| 订单窗口 | OrdersEdgeToEdgeController | 限定 SplashActivity 且命中订单搜索提示；不对普通主界面启用 insets 改写 |
| 直播输入/动作栏、弹幕、商品卡 | 导航模块直播分支 | 输入与动作栏共用动态位移；弹幕跟随同一 liveShift；商品卡按自己的锚点避让；两个旧固定抬高常量已删除 |
| 聊天主题/回复面板/状态背景 | ThemeViewRepairs、ChatBottomSurface、ChatReplyThemeFix、ChatStatusBackdrop | 保留配色与背景绘制，不再拥有聊天按钮/输入区的位置写入 |
| 会话恢复、刷新率、通知头像、设置页 | 对应独立模块 | 未发现它们移动抖音页面底部控件；设置页自身 padding 与抖音适配无关 |

## 本轮验证与边界

已安装 0.3.14 并进行实机检查：普通私聊输入区、键盘展开/关闭、附件面板展开/关闭、聊天历史滚动、禁发群聊底栏与浮动按钮、返回消息列表与首页、首页评论区。消息列表底边与输入区顶边保持相接；普通聊天收起后输入区底边为 2716px，导航区域 2716–2772px 保持沉浸。Compose 评论宿主只补 56px 一次。未输入或发送新消息。

静态检查覆盖全部源码中的位移、padding、margin、尺寸及缩放写入，并追踪测量 Hook 与导航/主题的入口关系。整合后的普通聊天控制器不再使用固定 56px；主题修复文件不再写 translationY。

本轮未逐页重新打开听抖音、订单、直播和全部音乐/玩法变体，也未逐帧录制每一种过渡；这些分支已完成代码关系检查，除移除无效常量与旧评论导航值读取外保留原行为。不能把静态分析等同于所有远端下发页面都已经实机覆盖。自定义壁纸、候选词行、原生浮动按钮隐藏/重建等条件分支仍应在后续出现对应场景时回归。

## 后续修改约束

- 输入区位置只改 ChatDockGeometry，列表测量只消费该控制器的 reserve。
- 不在主题模块新增位置修复；主题通过 UnifiedChatBridge 读取已绑定的布局对象。
- 父容器已承担安全区时，不再对子控件叠加同一个导航值。
- 区分导航安全区、原生控件内部间距、动画临时位置；不能把所有独立位移一概删除。
- 旧布局兼容处理必须有结构边界，不能只凭“在屏幕底部”就启用；记录绑定、应用和恢复的同一目标。

## 源码布局写入索引

包含应用与释放恢复写入；只列静态代码，不包含手机消息内容。

- `ChatDockGeometry.kt`：30、40。
- `ChatFloatingGeometry.kt`：43、47。
- `DecorNavigationInsetTransfer.kt`：36、50、61、86、89、99。
- `DouyinImmersiveNavModule.kt`：339、359、427、459、512、523、537、766、770、813、819、825、836、839、891、905、1089、1093、1447、1461。
- `HomeTabSafeArea.kt`：69、79、80、100、118、122、126、127。
- `OrdersEdgeToEdgeController.kt`：149、153、167、178、254、258、264、267。
- `PageNavigationSafeArea.kt`：74、228、239、242、277、284、299、300、309、337、341、347、349、352、364、367、368。
- `PublicVideoSafeArea.kt`：43、109。
- `VideoBottomOverlays.kt`：296、327。
- `VideoMediaSafety.kt`：65、104。
- `VideoPhotoInsets.kt`：53、77。
- `ChatBottomSurface.kt`：无直接位置/尺寸写入。
- `ChatListInsets.kt`：无直接位置/尺寸写入。
- `ChatReplyThemeFix.kt`：无直接位置/尺寸写入。
- `ChatScrollControlFix.kt`：无直接位置/尺寸写入。
- `ChatSessionRestore.kt`：无直接位置/尺寸写入。
- `ChatStatusBackdrop.kt`：无直接位置/尺寸写入。
- `DouyinSystemThemeModule.kt`：无直接位置/尺寸写入。
- `HighRefreshController.kt`：无直接位置/尺寸写入。
- `NativeChatRegistry.kt`：无直接位置/尺寸写入。
- `NotificationAvatar.kt`：无直接位置/尺寸写入。
- `ProfileQuickEntryThemeFix.kt`：无直接位置/尺寸写入。
- `RuntimeChatDiagnostics.kt`：无直接位置/尺寸写入。
- `ThemeContrastFixes.kt`：无直接位置/尺寸写入。
- `ThemeViewRepairs.kt`：无直接位置/尺寸写入。
- `UnifiedChatBridge.kt`：无直接位置/尺寸写入。
- `UnifiedModule.kt`：无直接位置/尺寸写入。
- `UnifiedSettingsActivity.kt`：20。
