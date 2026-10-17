# 字体说明

本项目自带两套开源字体，均为 **SIL Open Font License 1.1**，可免费用于个人与商业项目。

| 字体 | 用途 | 版权 | 项目地址 |
|---|---|---|---|
| Inter | 拉丁字母、数字、UI 控件 | Copyright (c) 2016 The Inter Project Authors | https://github.com/rsms/inter |
| Noto Sans SC | 中文正文与标题 | Copyright 2014-2021 Adobe (http://www.adobe.com/), Reserved Font Name "Source" | https://fonts.google.com/noto/specimen/Noto+Sans+SC |

字体文件由 Google Fonts 的 woff2 分片下载后自托管，通过 `unicode-range` 按需加载：
页面只下载实际用到的字符分片，不会一次拉全量。许可证全文见
https://openfontlicense.org/open-font-license-official-text/
