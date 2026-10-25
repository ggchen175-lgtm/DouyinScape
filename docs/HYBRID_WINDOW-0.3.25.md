# 0.3.25 测试：全屏混合窗口沉浸

月付使用 CJLiveDummyActivity 与独立 Dialog，读取到 AnnieX RadiusFrameLayout + SSWebView 的窗口内容仅2716高，系统底部导航背景56高。Activity窗口沉浸不会自动应用到Dialog。

复用 makeWindowEdgeToEdge，新增全屏 Dialog 的有限次结构识别：必须是 MATCH_PARENT 宽高，且包含原生 AnnieX RadiusFrameLayout 和 LynxView/SSWebView。命中后移除发现监听，不持续逐帧扫描。不凭月付标题或Activity名适配，不移动网页内部控件，不改还款逻辑，不注入JS或改支付数据。保留原生WindowInsets供页面自己的安全区使用。

首次命中在pre-draw前请求重新应用Insets和测量，并取消该次绘制。编译安装完成，OnePlus 15 / Android 17 / 抖音40.8.0实测重开后SSWebView窗口由1272×2716扩展到1272×2772，navigationBarBackground独立底栏消失，滚动截图确认内容延伸到小白条后方。未操作还款/升级/提现。该版本仍为测试版。
