package com.dailyweather.app.util

/**
 * 内置城市库（约 40 个主要城市，坐标为 WGS-84 近似值）：
 * 无高德/和风 key 时城市搜索的离线兜底（对齐南风"城市搜索无结果"前的可用层）。
 * 高德 key 注入后，搜索优先走 AmapSearchClient，本表只做拼音/常用名兜底。
 */
data class BuiltinCity(
    val name: String,
    val province: String,
    val pinyin: String,
    val lng: Double,
    val lat: Double,
)

object BuiltinCities {

    val ALL: List<BuiltinCity> = listOf(
        BuiltinCity("北京", "北京市", "beijing", 116.407, 39.904),
        BuiltinCity("上海", "上海市", "shanghai", 121.474, 31.230),
        BuiltinCity("广州", "广东省", "guangzhou", 113.264, 23.129),
        BuiltinCity("深圳", "广东省", "shenzhen", 114.058, 22.543),
        BuiltinCity("杭州", "浙江省", "hangzhou", 120.155, 30.274),
        BuiltinCity("南京", "江苏省", "nanjing", 118.797, 32.060),
        BuiltinCity("苏州", "江苏省", "suzhou", 120.585, 31.299),
        BuiltinCity("成都", "四川省", "chengdu", 104.066, 30.572),
        BuiltinCity("重庆", "重庆市", "chongqing", 106.551, 29.563),
        BuiltinCity("武汉", "湖北省", "wuhan", 114.305, 30.593),
        BuiltinCity("西安", "陕西省", "xian", 108.940, 34.341),
        BuiltinCity("长沙", "湖南省", "changsha", 112.939, 28.228),
        BuiltinCity("郑州", "河南省", "zhengzhou", 113.625, 34.747),
        BuiltinCity("天津", "天津市", "tianjin", 117.201, 39.085),
        BuiltinCity("青岛", "山东省", "qingdao", 120.383, 36.067),
        BuiltinCity("济南", "山东省", "jinan", 117.120, 36.652),
        BuiltinCity("厦门", "福建省", "xiamen", 118.089, 24.479),
        BuiltinCity("福州", "福建省", "fuzhou", 119.297, 26.074),
        BuiltinCity("合肥", "安徽省", "hefei", 117.227, 31.821),
        BuiltinCity("昆明", "云南省", "kunming", 102.833, 24.881),
        BuiltinCity("贵阳", "贵州省", "guiyang", 106.630, 26.648),
        BuiltinCity("南宁", "广西壮族自治区", "nanning", 108.366, 22.817),
        BuiltinCity("海口", "海南省", "haikou", 110.199, 20.044),
        BuiltinCity("三亚", "海南省", "sanya", 109.508, 18.247),
        BuiltinCity("哈尔滨", "黑龙江省", "haerbin", 126.534, 45.803),
        BuiltinCity("长春", "吉林省", "changchun", 125.325, 43.897),
        BuiltinCity("沈阳", "辽宁省", "shenyang", 123.432, 41.806),
        BuiltinCity("大连", "辽宁省", "dalian", 121.615, 38.914),
        BuiltinCity("石家庄", "河北省", "shijiazhuang", 114.515, 38.043),
        BuiltinCity("太原", "山西省", "taiyuan", 112.549, 37.857),
        BuiltinCity("呼和浩特", "内蒙古自治区", "huhehaote", 111.752, 40.842),
        BuiltinCity("兰州", "甘肃省", "lanzhou", 103.834, 36.061),
        BuiltinCity("西宁", "青海省", "xining", 101.778, 36.617),
        BuiltinCity("银川", "宁夏回族自治区", "yinchuan", 106.231, 38.487),
        BuiltinCity("乌鲁木齐", "新疆维吾尔自治区", "wulumuqi", 87.617, 43.793),
        BuiltinCity("拉萨", "西藏自治区", "lasa", 91.111, 29.970),
        BuiltinCity("香港", "香港特别行政区", "hongkong", 114.169, 22.319),
        BuiltinCity("澳门", "澳门特别行政区", "aomen", 113.549, 22.199),
        BuiltinCity("台北", "台湾省", "taibei", 121.565, 25.033),
        BuiltinCity("宁波", "浙江省", "ningbo", 121.550, 29.874),
        BuiltinCity("温州", "浙江省", "wenzhou", 120.699, 27.994),
        BuiltinCity("东莞", "广东省", "dongguan", 113.752, 23.021),
        BuiltinCity("佛山", "广东省", "foshan", 113.122, 23.029),
        BuiltinCity("无锡", "江苏省", "wuxi", 120.312, 31.491),
        BuiltinCity("常州", "江苏省", "changzhou", 119.946, 31.773),
        BuiltinCity("泉州", "福建省", "quanzhou", 118.589, 24.913),
        BuiltinCity("烟台", "山东省", "yantai", 121.391, 37.539),
        BuiltinCity("洛阳", "河南省", "luoyang", 112.454, 34.619),
        BuiltinCity("绵阳", "四川省", "mianyang", 104.679, 31.468),
    )

    fun search(keyword: String): List<BuiltinCity> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return ALL.filter { it.name.contains(kw) || it.province.contains(kw) || it.pinyin.startsWith(kw.lowercase()) }.take(10)
    }
}
