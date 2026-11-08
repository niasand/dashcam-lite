# 钛马星记录仪协议与重建说明

样本来自公开仓库 release `0.1`，两份都是完整 APK（zipflinger），不是 XAPK 分包。SHA256 与发布说明一致。

| 文件 | 版本 | versionCode | 字节 | SHA256 |
| --- | --- | --- | --- | --- |
| `5.4.240904.1_v_APKPure.apk` | 5.4.240904.1_v | 417254413 | 111314724 | `e2ee69f1a34b0d57af1b4d256f65241db567e0c54c076e36dfddcb992d986224` |
| `5.3.220526.1_APKPure.apk` | 5.3.220526.1 | 416535331 | 74143072 | `deebe901d6fa888b46ef26b8cdf27b43357c236279baa64c71b4e6e23617e3cd` |

**较新的是 5.4.240904.1_v**（2024-09-04，相对 2022-05-26 的 5.3）。

分析用了 REA `inspect-apk-signature`（jadx-headless-mcp 0.7.1）、apktool 2.11.1、jadx 1.5.3 和 readelf 可见的原生库清单。Craft Anything 的 README 要求 Rust 1.97 和独立编码代理；本环境 rustc 是 1.83，没有编译它的桌面程序。重建按它的 Study → 干净重写来做：协议按观察重写，没有把反编译源码拷进仓库。

## 应用身份

- 包名 `com.tima.carnet.m.main`，`android:sharedUserId` 同名。清单：`AndroidManifest` / `SmallApp`。
- 显示名「钛马星」。`connect_help.htm` 写明它不是记录仪品牌，而是用来连接记录仪的手机应用。
- minSdk 23，targetSdk 28，compileSdk 31。5.4 的 Android Gradle Plugin 元数据是 4.2.2。
- 两版签名相同：v1 + v2，证书 `CN=carnet`，序列号 `4A447712`，SHA256 `ce058f4172009694380fe0883e9a58b2daadf5e32e35d0aba6e2a626523959b3`，有效期 2015-01-12 至 2040-01-06。REA 只做结构读取，不验证证书链。
- 启动入口 `com.tima.carnet.m.main.LaunchActivity`。

记录仪能力按四位插件码装进宿主。`assets/bundle.json` 把插件码映射到 `novatek`、`mstar`、`hisilicon`、`jieli`、`goke`、`grain`、`ark` 等包名；`assets/code_book.json` 再标芯片代号（NT9X、AI8X、GM8X、GK86）。设置文案在 `assets/settings_com.tima.app.dr.*.json`。

## 怎么连上记录仪

本地连接是记录仪自己开的 Wi-Fi 热点，手机连上去之后访问网关。没有发现用于记录仪配对的 BLE GATT。清单里的蓝牙权限服务于车机互联，不是摄像头链路。

热点名猜测在 `com.tima.app.common.dr.connect.DRWifiNameKit.guessDVRWifi`：名称转小写后包含 `dvr`、`mini`、`papago`、`mxsx`、`mtsx`、`teye`、`wjc`、`cardv`、`wykj`、`lld`、`decai` 之一。帮助页给出的默认密码是 `12345678`、`123456789`、`012345678`、`1234567890`。

网络绑定在 `com.tima.carnet.util.WiFiUtils`：

- `lockWiFiNetwork`：`NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)`，然后 `registerNetworkCallback`。没有 `WifiNetworkSpecifier`，也没有去掉 `NET_CAPABILITY_INTERNET`。
- `bindNetwork`：API 23+ `ConnectivityManager.bindProcessToNetwork`，更早则 `setProcessDefaultNetwork`。
- `lockWiFiNetworkIfNeeded`：Wi-Fi 已经是活动网络就跳过；否则，或用户打开「强制绑定」时再绑。
- `unBindWifiNetwork`：`bindProcessToNetwork(null)`。

设备 IP 默认取 DHCP 网关，对不上插件默认地址时只记一条警告。各插件默认地址：

| 协议 | 默认 IP | 位置 |
| --- | --- | --- |
| 联咏 Novatek | 192.168.1.254 | `com.tima.app.dr.novatek.device.DeviceKit.deviceIP` |
| 晨星 / SigmaStar | 192.72.1.1 | `com.tima.app.dr.mstar.device.DeviceKit` |
| 海思 | 192.168.11.1 | `com.tima.app.dr.hisilicon.device.DeviceKit` |
| 杰理 | 192.168.1.1 | `com.tima.app.dr.jieli.device.DeviceKit` |
| 国科微 Goke | 192.168.63.9 | `com.tima.app.dr.goke.device.DeviceKit` |
| Grain | 192.168.201.1 | `com.tima.app.dr.grain.device.DeviceKit` |
| Ark | 192.168.8.1 | `com.tima.app.dr.ark.device.DeviceKit` |

HTTP 客户端统一 `Connection: close`，连接 10 秒，读写 60 秒（`DeviceHttpEngine` 静态初始化）。

## 联咏（主协议）

实现类：`com.tima.lib.dr.res.devices.novatek.device.beans.DeviceRequest`、`DeviceApi`、`DeviceHttpEngine`，会话在 `com.tima.app.dr.novatek.device.DeviceKit` 和 `helper.DrCallbackImpl`。

URL：`http://{ip}/?custom=1&cmd={id}`，参数用 `&par=` 或 `&str=`。成功时 XML 里 `<Status>0</Status>`。`DeviceResponse.parse` 读 `Cmd`、`Status`、`Value`。

握手（`DeviceKit` 内部检查线程）：

1. `cmd=2016` 录像状态。`Value > 0` 视为正在录像（`DeviceKit.isRecording`）。
2. `cmd=3012` 固件，`<String>` 可能是 `版本@QX302`。
3. `cmd=3005&str=yyyy-MM-dd`，`cmd=3006&str=HH:mm:ss`。
4. `cmd=3029` 读 SSID / 密码。
5. `cmd=3014` 当前设置。这里的 `<Status>` 是设置值，不是错误码（`AllSettingsResponse.parse`）。
6. `cmd=3031` 菜单，`cmd=3030` 分辨率列表。
7. 探测 554 端口决定预览走 RTSP 还是 HTTP。RTSP 为 `rtsp://{ip}/xxxx.mov`，否则 `http://{ip}:8192`（`getLiveRtspUrl` / `getLiveHttpUrl`）。
8. 连上 TCP `3333`，读异步通知。行 `<Cmd>3020</Cmd>` 的下一行是状态码（`SocketListener`）。

文件：

- 列表只有一条 `cmd=3015`。客户端再分类（`DrCallbackImpl.loadRemoteFiles`）：普通视频 = 非图片且 `ATTR & 1 == 0`；紧急/锁定 = 非图片且 `ATTR & 1 == 1`；照片 = 路径以 JPG/JPEG 结尾（`MediaFile`）。
- 字段：`NAME`、`FPATH`、`SIZE`、`TIME`、`ATTR`（`FileListResponse.cusParse`）。
- 下载 URL 把 `FPATH` 的反斜杠换成斜杠，从第一个 `/` 起拼到 `http://{ip}`。
- 缩略图是下载 URL 再加 `?custom=1&cmd=4001`。
- 删除 `cmd=4003&str={FPATH}`。

录像和模式（`DeviceKit` 常量）：照片 0、录像 1、回放 2。

- 回放：`cmd=3001&par=2`（`switchModePlayback`）。进入回放会停循环录像。
- 开始录像：先 `cmd=3001&par=1`，再 `cmd=2001&par=1`（`DrCallbackImpl.startRecord`）。
- 停止录像：同样先回录像模式，再 `cmd=2001&par=0`。
- 拍照 `cmd=1001`；录像中拍照走 `cmd=2017`。
- 切镜头 `cmd=3028&par=`，参数在 `0..5` 循环。
- 设置写回就是对应 cmd 加 `par`。已知项：2002 分辨率、1002 照片、2011 碰撞、2003 循环、2007 录音、2008 水印、2006 移动侦测、2004 宽动态、2012 录像模式。
- 剩余空间 `3017`，卡状态 `3024`（值为 1 才算可用，`isSDReady`），格式化 `3010`。
- 心跳 `cmd=3016`，`NovaHeartBeatKit.startHeartBeat` 默认 5000 ms。基础 `novatek` 插件连接成功后只开 3333 通知口；`novatek_wh`、`novatek_bszy_dcdz` 等在 `DeviceKit` 连接成功路径调用 `DrHelper.startHeartBeat`，断开时 `stopHeartBeat`。

## 离开时如何恢复录像

这是原版真正发给设备的序列，不是断开 Wi-Fi 本身。

预览界面 `com.tima.app.common.dr.play.PlayActivity`：

- `onPause`：若不是刚被 Home 广播打断，调用 `DrHelper.notifyDvrAppExit`，完成后 `startRecord`，再 `doExitPlay`。
- `onDestroy`：若标记仍在预览流程里，`notifyDvrAppExit` 完成后 `startRecordAsync`。
- 基础联咏插件的 `notifyDvrAppExit` 和 `doExitPlay` 是空实现（`DrHelper.DrCallback` 默认直接成功）。`startRecord` / `startRecordAsync` 才是命令：`switchModeMovie` → `DeviceApi.startRecord`，即 **`3001&par=1` 然后 `2001&par=1`**。
- 打开相册 `doOpenDrMediaList` 会 `switchModePlayback`（`3001&par=2`），所以浏览文件期间录像是停的。回到预览或离开应用时再走上面的恢复。

`DeviceKit.disConnect` 本身不发 2001。它解绑 Wi-Fi、停 3333、停延迟任务。带心跳的插件变体会先 `stopHeartBeat`。部分 4G 插件的 `doExitPlay` 还会 `releaseStream`（例如 `novatek_bszy_dcdz.helper.DrCallbackImpl`）。

进程被直接杀掉时，原版没有单独的记录仪前台服务去补发命令。能依靠的是：通知 TCP 3333 断开，以及心跳 3016 停止之后固件自己超时回到录像。Wi-Fi 闪断时 `startDelayDisConnect` 会等 10 秒再走 `disConnect`。

其他芯片的「恢复录像」对应预览离开时的 `startRecord`：

- 晨星：读 `Camera.Preview.MJPEG.status.record`，不是 `Recording` 就 `action=set&property=Video&value=record`。
- 海思：`setworkmode.cgi?&-workmode=NORM_REC`，再 `workmodecmd.cgi?&-cmd=start`。彻底断开时 `client.cgi` `operation=unregister`（`hisilicon.device.DeviceKit.disConnect`）。心跳是 `checkconnect.cgi`，3 秒。
- 国科微：`type=system&action=setrecstatus&recstat=1`。
- Grain：`/?cmd=101`。
- 杰理：TCP 3333 上 `PUT VIDEO_CTRL`，`status=1`。

## 其他芯片要点

晨星 / SigmaStar，`com.tima.lib.dr.res.devices.mstar.device.DeviceApi`：

- `GET /cgi-bin/Config.cgi?action=get|set|del|dir|reardir&property=&value=`
- 文件：`dir` 前路、`reardir` 后路；`property` 为 `Normal` / `Event` / `Park` / `Photo`；照片 `format=jpeg`，其余 `format=all`。
- 菜单 `GET /cdv/cammenu.xml`。直播 `rtsp://{ip}/liveRTSP/av{n}`，`n` 来自设备应答。
- 校时 `TimeSettings=yyyy$MM$dd$HH$mm$ss`。格式化 `SD0=format`。镜头 `setcamid`，`front` / `rear`。
- 应答以 `0` 或 `715` 开头算成功（`DeviceResponse.parse`）。

海思，`com.tima.lib.dr.res.devices.hisilicon.device`：

- 前缀 `/cgi-bin/hisnet/`。目录默认 `norm`、`emr`、`park`、`photo`；后路目录名含 `back_` 或 `b_`（`MediaFoldersKit`）。
- 直播 `rtsp://{ip}:554/livestream/{通道}` 或 `http://{ip}:8192`。
- 先 `client.cgi?&-operation=register&-ip=` 手机地址。

国科微，`com.tima.app.dr.goke.device.DeviceApi` 和 `Keys`：

- `/cgi-bin/Control.cgi?type=system|file|image|param|hbt|snapshot&action=`
- 普通 `searchfile`，紧急 `searchevent`，照片 `searchimg`。路径前缀 `/sdcard/DVR/VIDEO/`、`EVENT/`、`PIC/`。
- 直播 `rtsp://{ip}/live/udp/ch1_0` 与 `ch3_0`。心跳 `type=hbt`。格式化 `action=formattf`。版本 `action=getversion`。

Grain，`com.tima.lib.dr.res.devices.grain.device.beans.DeviceRequest`：

- `/?cmd=`，可选 `type`、`param`、`value`。101 开始录像，102 停止，103 状态，104 切镜头，105 拍照，201 文件（type 0/1/2/3 = 普通/紧急/停车/照片），202 删除，301 写设置，305 设备信息，306 校时 `yyyy-MM-dd$HH:mm:ss`，308 格式化，501 心跳（10 秒）。
- 直播 `rtsp://{ip}/live/ch00_0`。

杰理，`com.tima.app.dr.jieli.device`：

- TCP `192.168.1.1:3333`。帧是 ASCII `CTP:`，随后小端 uint16 主题长度、主题、小端 uint32 JSON 长度、JSON `{op,param}`（`BaseData.encode`）。
- 进入 `PUT APP_ACCESS` `type=0` `ver=1.0`。心跳 `GET CTP_KEEP_ALIVE`。文件 `PUT FORWARD_MEDIA_FILES_LIST` `type=1`，返回的 path 再用 `http://{ip}:8080/{path}` 拉 JSON。删除 `PUT FILES_DELETE` `path_0`。格式化 `PUT FORMAT`。校时 `PUT DATE_TIME`。
- 直播 `rtsp://{ip}:554/avi_pcma_rt` 或 `264_rt/XXX.sd`。

Ark 的直播地址形态与联咏相同，默认网关是 `192.168.8.1`。

4G 远程不是本地热点协议。`com.tima.carnet.p2p` 使用 ThroughTek TUTK（`libIOTCAPIs`、`libAVAPIs`、`libRDTAPIs`）。重建不包含这条云链路。

## 两版差异

命令号和默认 IP 在两份 DEX 里都在。签名证书相同。5.4 多了 `classes7.dex`、`libdevInfo.so`、`libzeus_direct_dex.so`，去掉了 5.3 的 `libEncryptor.so`。5.4 的插件更多，包括 `novatek_jxw_4g` 及双路远程变体；`bundle.json` 仍写着 2022-02-19。本地 CGI 的恢复录像顺序没有改：仍是切回录像模式再打开录像。两版都没有 `WifiNetworkSpecifier`。

## 功能取舍

保留（和记录仪本地会话直接相关）：

- Wi-Fi 绑定、握手、心跳、通知口
- 实时预览（RTSP / MJPEG）
- 普通、紧急/锁定、照片列表，前后路（晨星 `dir`/`reardir`，海思目录名）
- 缩略图、下载、删除
- 回放期间切到回放模式；离开、后台、划掉任务时恢复录像
- 开始/停止录像、切镜头、拍照命令
- 设置读写、校时、卡状态、格式化、固件/设备信息

去掉（宿主里能对上的 SDK 和功能）：

- 广告：AdMob（`com.google.android.gms.ads.APPLICATION_ID`）、穿山甲 Pangle（`libpangleflipped.so`、`TT_PANGOLIN`）、优量汇 GDT（`assets/gdt_plugin/gdtadv2.jar`）、快手 `ksad_*`、百度 Mobads、倍孜
- 统计：友盟 `libumeng-spy.so`
- 推送：个推 `getui.permission.GetuiService`、小米 `MIPUSH_RECEIVE`、`com.tima.carnet.m.main.push`
- 账号 / 支付 / 会员：`UserManage`、微信登录与支付 `wxapi.WXPayEntryActivity`、`VIPPayActivity`、发票模块
- 社区：`com.tima.app.main.sns`、`sns2`，微博 `libweibosdkcore.so`
- 地图轨迹：高德 `com.amap`，轨迹页 `TraceActivity`
- 4G 云与 P2P：TUTK、`libCtaApiLib.so`、`libnetbridge.so`
- 车机投屏、QQ 音乐插件 `com.tima.app.vt.qqmusic`
- 原版还申请了通讯录、通话记录、电话、安装包、悬浮窗、查询全部包名等，重建都不需要

## 重建应用

新应用包名 `com.niasand.dashcamlite`，名称「行车记录仪Lite」。协议在 `:protocol`，按上面的 URL 和帧格式重写，不链接原版代码。

连接使用 `requestNetwork`，请求里去掉 `NET_CAPABILITY_INTERNET`，再 `bindProcessToNetwork`。填写热点名时走 `WifiNetworkSpecifier`，这是原版没有、但在新系统上加入指定热点所需要的。连接期间以前台服务保住进程；`onTaskRemoved` / `onDestroy` 发送恢复录像。应用进入后台时同样发送，和 `PlayActivity.onPause` 一致。真正的 SIGKILL 仍可能来不及发命令，此时只能依赖固件在心跳和 3333 断开后的超时，这一点报告里保持和原版相同的限制。

下载写入 MediaStore 的 `Movies/DashcamLite` 或 `Pictures/DashcamLite`。已有部分文件时带 `Range`，服务器返回 206 则追加，否则重下。格式化有确认框。关于页有「© 2026 版权所有 yangzhiwei.727」。

## 需要真机才能确认的点

- 各固件对 `2001` 在已经录像时的返回码，以及 3333 断开后多久自动开录。
- RTSP 路径 `xxxx.mov`、`liveRTSP/avN`、`livestream/{cam}` 是否和具体机型一致；5.4 会在 554 和 `:8192` 之间探测。
- 海思注册用的手机 IP，原版取 `WiFiUtils.getWifiIP()`。
- 杰理文件 JSON 的字段名在不同固件上可能不只 `name`/`path`。
- 晨星缩略图把路径里的 `SD` 换成 `thumb`，有的机型目录名不同。
- `WifiNetworkSpecifier` 需要用户在系统对话框里点同意，应用不能静默连上热点。
