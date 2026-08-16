# KaTeX 本地资源（模块 08 · T02）

本目录放置 KaTeX 离线资源，供 PDF 模板 `file://` 加载（断网可生成 PDF）。

## 文件清单（待下载，V1.0 前补齐）

| 文件 | 说明 |
|---|---|
| `katex.min.js` | KaTeX 核心（含 auto-render 扩展？不，见下） |
| `katex.min.css` | 基础样式 |
| `fonts/*` | woff2 字体（KaTeX_Main / Math / AMS / Caligraphic / Fraktur / SansSerif / Script / Size1-4 / Typewriter） |
| `contrib/auto-render.min.js` | `renderMathInElement` 注入用（模板 paper.html 调用） |

## 下载方式（任选其一）

```bash
# 方式一：npm 包内拷贝（推荐，版本锁定）
npm pack katex@0.16.11
tar -xzf katex-0.16.11.tgz
cp -r package/dist/katex.min.js package/dist/katex.min.css package/dist/fonts ./
cp -r package/dist/contrib/auto-render.min.js contrib/

# 方式二：CDN 直下
curl -L -o katex.min.js https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js
curl -L -o katex.min.css https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css
curl -L -o auto-render.min.js https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js
# fonts 目录逐字体下载：https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/fonts/<名字>.woff2
```

## 体积约束

- 打包进 APK 后 gzip 增量 **< 2MB**（katex.min.js ~270KB + css ~26KB + woff2 字体 ~1.1MB，满足）
- 模块验收：APK 体积增量 < 2.5MB

## 接入点（代码侧已就绪）

- `assets/templates/paper.html` 头部相对引用：`../katex/katex.min.css`
- 打印前注入脚本：`renderMathInElement`（失败兜底显示 `$...$` 原样，见 paper.html 内 `safeRenderMath`）
- 渲染驱动：`com.misoup.app.output.pdf.PaperRenderer`

> ⏸ 状态：资源文件待下载（V1.0 前完成）；缺文件时 PDF 模板跳过公式渲染，
> 题干中的 `$...$` 原样显示，不影响打印主链路。
