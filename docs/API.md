# API 接入检查（2026-09-09）

本应用直接连接数据提供方，不需要开发者自己的服务器，也不内置共享密钥。第三方可看到请求的 IP 和扫描区域坐标。

| 用途 / 服务 | 认证与请求方式 | 本次结果 |
|---|---|---|
| adsb.lol 航班 | 无需密钥；`https://api.adsb.lol/v2/point/{lat}/{lon}/{radius_nm}` | Mac 与手机均拿到真实飞机；反复重装测试时也遇到 HTTP 429 |
| OpenSky 航班 | 匿名或 OAuth2 client credentials；`/api/states/all` 经纬度矩形 | 已实现解析与 token 更新；匿名空响应与字段单位已测试，未使用用户账户登录 |
| airplanes.live 航班 | 同类 `/v2/point` 格式 | 实际响应要求邮件联系 `contact@airplanes.live`，提供项目链接及说明，因此不作为默认源；未代发邮件 |
| adsbdb 航线 / 机型 | 无需内置密钥；`/v0/callsign/{callsign}`、`/v0/aircraft/{icao24}` | 实测 QFA7356 返回 MEL–LST，手机 JTE7489 返回 MEL–ADL |
| Open-Meteo 当前天气 | 无密钥，`/v1/forecast?current=...` | 已接入温度、湿度、风速、风向 |
| RainViewer 降雨 | 无密钥，`/public/weather-maps.json`，读取最新 `radar.past`，下载中心坐标瓦片 | 已接入；免费接口最大 zoom 7，不假设提供未来预测帧 |
| 原作者地图瓦片 | GitHub Pages，10×10 度分格 | 真实 S40E140 瓦片在设备解析、绘制通过；损坏 CRC 被拒绝 |

## 刷新、额度与断网

所有航班数据源默认每 15 秒请求，用户可设 15–900 秒。这是应用请求策略，不是服务端保证；[adsb.lol 官方说明](https://github.com/adsblol/api/blob/main/README.md)明确其限流随负载动态变化。失败指数退避，429 尊重服务端 Retry-After；来源冷却时间存储在手机，重启不直接重试冷却中的来源。手动选择来源时不会偷偷换源；“自动”模式优先 adsb.lol，再尝试 OpenSky。退出前台后停止发起周期轮询，已发出的请求可能完成。

OpenSky 官方文档当前列出：匿名每日 400 credits，普通账户 4000，活跃 feeder 8000。状态查询视矩形面积消耗 1–4 credits。应用匿名最少 240 秒、登录最少 30 秒，再按面积成本提高间隔；这不是额度保证，跨日期变更线、账号等级和服务端实际限流仍以返回值为准。

航线查询与位置轮询分开，每次间隔至少 2 秒，自动预取最近最多 20 个合适的航班呼号。成功缓存 6 小时，失败缓存 30 分钟。结果同时缓存 IATA 航班号、航空公司名称与代码、起点和终点。首次出现的 `UNKNOWN–UNKNOWN` 可能仍在排队，也可能服务没有此呼号的航线；警务、训练和地面车辆通常没有普通机场对。点选其他飞机可单独请求航线。航线数据库不代表实际落地、备降或航班状态。

航空公司本地身份库含常见航司代码、名称和品牌主色，完整运营方名称还可回退到随 App 提供的 6004 条 ICAO 运营方库。获得 IATA 代码后，App 会尝试从 Google 航班静态图片地址获取 70 px 标志并在应用缓存中保留 30 天。该图片地址不是本项目控制的公共 API，失败时只使用本地品牌色代码或 `UNKNOWN`，不会阻塞航班页。标志颜色不会统一染绿，暗色像素在黑底上会提亮以保持可见。

天气每 10 分钟刷新。航班失败保留最近位置，同时标明更新时间及陈旧状态；不会用模拟飞机填补错误。地图与离线机型在下载/安装后可离线显示。

## 来源文档

- [OpenSky REST API 与额度](https://openskynetwork.github.io/opensky-api/rest.html)
- [adsb.lol API](https://api.adsb.lol/docs)
- [airplanes.live API 文档](https://airplanes.live/api-docs/)
- [adsbdb](https://www.adsbdb.com/)
- [Open-Meteo 免费非商业 API 条款](https://open-meteo.com/en/terms)
- [RainViewer Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html)
- [原作者地图数据仓库](https://github.com/delphicchen/flight-radar-maps)

免费不等于无限制或允许商业发行。Open-Meteo 免费端点有非商业和频率限制；原项目本身也是 CC BY-NC-SA 4.0。当前按个人非商业使用制作，未替用户申请、购买或发布任何 API 服务。

## 街道底图

0.1.1 新增 OpenStreetMap 标准瓦片，仅获取用户正在查看的视野，不提供预下载功能。设置独立 User-Agent，缓存至少 7 天，过期使用 If-Modified-Since 条件请求；地图上持续显示版权署名。瓦片通过地理坐标网格映射到雷达投影，缩放时和航班共用同一显示范围。服务不可用时保留原项目轮廓底图并显示重试状态。

[OSM Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) · [OpenStreetMap 版权](https://www.openstreetmap.org/copyright)


## METAR 与位置

[AWC Data API](https://aviationweather.gov/data/api/)：按所选 ICAO 单站获取最新 JSON，默认每 5 分钟检查；包含原始 METAR 和官方结构化字段，不调用生成式翻译服务。目录取自官方站点缓存，离线搜索最近报告站。站点观测发布频率独立于 App 请求频率。

地址搜索先调用 Android Geocoder，无结果时使用 [Photon](https://github.com/komoot/photon) 公共服务。仅点击时查询、最多 8 个候选、2 秒请求门限、32 条内存缓存，尊重 429 重试时间，不自动补全、不批量查询。Photon 要求合理请求量，不保证服务可用；输入地址会发送至相应服务。当前位置使用 Android LocationManager，仅用户主动点击时获取，25 秒超时，不存储位置历史。

## 更快的航班数据选择（2026-09-09 核对）

- adsb.lol 公共接口：动态限流，当前应用最短请求间隔为 15 秒，不承诺每次都有新位置。
- [adsb.lol feeder re-api](https://www.adsb.lol/docs/feeders-only/re-api/)：贡献接收站数据后按站点 IP 授权，手机异地访问需要自己的 VPN / 代理。当前未配置。
- [ADS-B Exchange Community API](https://adsbexchange.com/community/developer-hub/)：官方列 $10/月、10,000 次请求、位置数据 500ms 更新。500ms 是上游数据频率，不等于套餐允许全天候如此轮询；10,000 次在每 5 秒请求时约够 13.9 小时。需要用户自己的付费订阅与密钥，当前未购买或接入。
- [ADS-B Exchange 企业数据](https://www.adsbexchange.com/data-products/)：有 5 秒、500ms、250ms 和 streaming 产品，需要商业订阅。
- 自建 ADS-B 接收器/readsb：适合附近航班持续高频查看，覆盖受天线与地形影响，需要额外接收硬件，当前未连接。
