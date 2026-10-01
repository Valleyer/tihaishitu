# 本地美术素材

背景与人物图片由开发阶段使用内置 image_gen 工具生成，项目运行时不调用图片接口或第三方图库。

| 文件 | 用途 |
| --- | --- |
| frontend/public/art/academy.png | 首页与兼容场景背景 |
| frontend/public/art/portraits/*.jpg | 六张独立人物头像：男女主角、陆承明、顾怀安、沈砚、林知微 |
| frontend/public/art/locations/*.jpg | 七张独立地点背景与一张青溪县地图底图 |

所有图片的文件名、当前尺寸、建议尺寸、完整生成提示词和替换路径，以 `docs/map-background-assets.md` 的统一资产总表为准。以后新增图片也必须先登记到该文件。

人物为虚构古风角色。头像路径、无障碍名称和默认取景位置都在 content/portraits.json 配置，背景引用在 game.json 与 maps.json。替换图片后保持路径，或同步修改配置。

## 背景生成提示词

Use case: historical-scene. Asset type: illustrated background for a Chinese historical text role-playing game. Wide cinematic 16:9 composition. A humble riverside academy in a fictional late imperial Chinese county at dusk, wooden study room with open lattice doors in foreground right, writing desk, brush pot, scrolls, amber oil lamp; beyond courtyard, tiled roofs, pine branches, stone bridge, misty layered mountains and a tiny scholar in plain dark robes approaching the academy. Painterly sophisticated Chinese ink-wash mixed with richly detailed traditional game concept art, atmospheric deep teal charcoal shadows and muted antique gold lights, tangible aged wood and parchment, subdued elegant palette, no fantasy magic or exaggerated palace. Foreground left has darker calm negative space suitable for game title overlay; main architectural interest center-right. No text, no typography, no logo, no interface, no watermark. High quality narrative environment illustration.

## 旧版立绘生成提示词

Use case: historical-scene. Asset type: five portrait cards in a single sprite sheet for a Chinese historical text RPG. A very wide 5-column contact sheet, five equal-width rectangular portrait panels in one horizontal row, full-bleed edge-to-edge, no gutters, no borders, no text. Each portrait shows one fictional ancient Chinese person from mid-chest upward, head centered in that person's column, eyes in upper third. Left to right: (1) young male poor scholar, modest muted teal-gray crossed-collar robe and simple black scholar cap, thoughtful intelligent face; (2) elderly male Confucian tutor, gray beard, dark moss robes, kind strict expression; (3) young male fellow student from a respectable family, pale sage robe, well-groomed elegant restrained expression; (4) middle-aged male county clerk, gray-blue plain clothes and simple dark cap, cautious quiet serious expression; (5) young female scholar, modest muted teal-gray scholar robe, dark hair tied up with a simple wooden hairpin, thoughtful intelligent face. Historically inspired Chinese clothing, no fantasy armor. All portraits in the same sophisticated hand-painted historical RPG illustration style, realistically rendered facial anatomy, subtle brushwork, dark desaturated teal vignette backgrounds, warm amber rim lighting, muted antique gold highlights. Not cartoons, no chibi, no photoreal camera, no smiling exaggerated expressions. Every head and shoulder kept safely within its own column with generous top margin. No letters, no symbols, no watermarks.

## 新版独立头像规范

每张头像采用 1:1 方图，当前交付尺寸为 768×768 JPEG。构图以胸像为主，头顶与发饰保留安全边距，面部在缩小到 80px 时仍应清楚。背景使用浅绢纸、淡墨植物或书卷纹样，避免复杂建筑抢夺人物轮廓。角色服色与身份区分：主角深青或淡青、陆承明玄金、顾怀安月白、沈砚玄赤、林知微玉白与浅珊瑚。

本轮最终生成提示采用：清雅古风数字插画、细腻线稿、柔和水墨设色、半身近景、浅绢纸背景、明确身份服饰、无文字水印。参考图只用于概括视觉气质，没有复刻具体人物、姿势或构图。

## 后续替换

背景建议横构图、为文字保留安静的暗部。头像建议保持方形和相近的面部比例；需要微调裁切时只改 portraits.json 的 position。大图会影响首屏加载，可在后期统一压缩为 WebP，并同步修改配置路径。
