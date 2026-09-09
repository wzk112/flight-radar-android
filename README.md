# Flight Radar Android

原生 Java Android 飞行雷达与点阵航班显示屏。基于 [delphicchen/esp32_flight_radar](https://github.com/delphicchen/esp32_flight_radar) 改编，移除了 Home Assistant 集成。

## 功能

- ATC 风格雷达：飞机方向、轨迹、地图、航空图层、双指缩放和触摸详情。
- 最近航班：单架或多架；航班号、呼号、起降地、机型、距离、高度、地速和地面航迹方向 TRK。
- 100 个内置航司 Logo、离线运营商资料、在线图片补齐及失败缓存。
- Open-Meteo 天气、机场 METAR 原文与中文解读；定位、地址或经纬度选中心。
- 全局地面飞机开关；常亮、像素移动、闲置调暗和定时黑屏休息。
- 四组闹钟、截图分享和 Android 系统设置入口。

## 自适应布局（v1.0.0-beta.1）

按当前窗口的 dp 宽高选择布局，兼顾旋转和分屏。宽横屏使用侧边导航，窄屏使用顶部导航；天气按内容宽度切换双栏与上下布局。窄屏最近航班纵向排列，指标分两列；矮窗口和大字号下提供滚动，避免硬挤进一页。点阵控件保留系统尺寸和自动字号计算。

设置中的横屏锁定仍可使用；关闭后支持自动旋转。最低 Android 11 / API 30。

## 构建

需要 JDK 17、Android SDK Platform 35。使用 Android Studio 打开仓库，或在本地 `local.properties` 设置 `sdk.dir` 后运行：

```sh
./gradlew assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。仓库提供可选的 [GitHub Actions 构建模板](docs/android-ci.yml.example)，可复制到 `.github/workflows/android.yml` 启用。该应用没有收费后端；API 账号和密钥只在设备上配置，不包含在源码或 APK 中。

公开测试版使用项目专用发布证书，并启用 APK Signature Scheme v2。发布密钥和密码不进入 Git；本地发布构建通过 `FLIGHTRADAR_KEYSTORE`、`FLIGHTRADAR_STORE_PASSWORD`、`FLIGHTRADAR_KEY_PASSWORD` 环境变量注入。侧载仍可能触发 Android、小米安全组件或 Play Protect 的未知来源提示，这与 APK 是否签名是两回事。证书指纹见 [发布签名说明](docs/RELEASE-SIGNING.md)。

## 数据与限制

默认航班刷新间隔 30 秒；普通网络失败按 15/30/60 秒重试，429 单独退避并尊重服务端等待时间。天气每 10 分钟、METAR 每 5 分钟更新。详情见 [刷新规则](docs/REFRESH-POLICY.md)。

航班源为 adsb.lol、OpenSky 和 airplanes.live，航线补充使用 adsbdb。覆盖、额度和访问权限由供应商决定，不保证中国大陆或任何地区完整覆盖。FR24 与飞常准尚未接入。缺失字段保留 UNKNOWN，不把估算航线冒充实际航线。

防烧屏只能降低风险，不能保证长期 OLED 使用无损。此应用仅供爱好展示，不用于导航或实际空管。

## 测试

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.flightdesk.radar.test/org.flightdesk.radar.DeviceChecks
```

布局截图测试使用 `-PadaptiveChecks` 构建测试 APK，再运行 `org.flightdesk.radar.AdaptiveChecks`，可传 `-e orientation portrait`。测试包含模拟航班，截图不代表实时覆盖。

实体设备验证：Xiaomi 17 Ultra / Android 16。平板比例在该设备通过 Android 显示尺寸模拟，不等同实体平板验证。测试工具会临时触发闹钟，建议使用专用测试设备。

## 许可与来源

保留上游 **CC BY-NC-SA 4.0** 非商业许可，见 [LICENSE](LICENSE) 和 [NOTICE.md](NOTICE.md)。这是公开源码项目；由于非商业限制，不宣称符合 OSI 的无限制开源定义。

航司代码衍生数据库采用 ODbL，见 [数据库来源](app/src/main/assets/AIRLINE-DATA-LICENSE.txt)。航司 Logo 为各权利人商标，用于识别，不受项目许可证重新授权；见 [图片来源](app/src/main/assets/airline-logos/SOURCES.txt)。第三方数据服务的使用需遵守其各自条款。
