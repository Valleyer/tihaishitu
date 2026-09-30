# 本地美术素材

两张图片由本次开发使用内置 image_gen 工具生成，项目运行时不调用图片接口或第三方图库。

| 文件 | 用途 |
| --- | --- |
| frontend/public/art/academy.png | 首页、章首与地点背景，可复用取景 |
| frontend/public/art/portraits.png | 横排五列立绘：男书生、先生、同窗、文书、女书生 |

人物为虚构古风角色。立绘可重复使用；映射在 content/portraits.json，背景引用在 game.json 与 maps.json。替换图片后保持路径，或同步修改配置。

## 背景生成提示词

Use case: historical-scene. Asset type: illustrated background for a Chinese historical text role-playing game. Wide cinematic 16:9 composition. A humble riverside academy in a fictional late imperial Chinese county at dusk, wooden study room with open lattice doors in foreground right, writing desk, brush pot, scrolls, amber oil lamp; beyond courtyard, tiled roofs, pine branches, stone bridge, misty layered mountains and a tiny scholar in plain dark robes approaching the academy. Painterly sophisticated Chinese ink-wash mixed with richly detailed traditional game concept art, atmospheric deep teal charcoal shadows and muted antique gold lights, tangible aged wood and parchment, subdued elegant palette, no fantasy magic or exaggerated palace. Foreground left has darker calm negative space suitable for game title overlay; main architectural interest center-right. No text, no typography, no logo, no interface, no watermark. High quality narrative environment illustration.

## 立绘生成提示词

Use case: historical-scene. Asset type: five portrait cards in a single sprite sheet for a Chinese historical text RPG. A very wide 5-column contact sheet, five equal-width rectangular portrait panels in one horizontal row, full-bleed edge-to-edge, no gutters, no borders, no text. Each portrait shows one fictional ancient Chinese person from mid-chest upward, head centered in that person's column, eyes in upper third. Left to right: (1) young male poor scholar, modest muted teal-gray crossed-collar robe and simple black scholar cap, thoughtful intelligent face; (2) elderly male Confucian tutor, gray beard, dark moss robes, kind strict expression; (3) young male fellow student from a respectable family, pale sage robe, well-groomed elegant restrained expression; (4) middle-aged male county clerk, gray-blue plain clothes and simple dark cap, cautious quiet serious expression; (5) young female scholar, modest muted teal-gray scholar robe, dark hair tied up with a simple wooden hairpin, thoughtful intelligent face. Historically inspired Chinese clothing, no fantasy armor. All portraits in the same sophisticated hand-painted historical RPG illustration style, realistically rendered facial anatomy, subtle brushwork, dark desaturated teal vignette backgrounds, warm amber rim lighting, muted antique gold highlights. Not cartoons, no chibi, no photoreal camera, no smiling exaggerated expressions. Every head and shoulder kept safely within its own column with generous top margin. No letters, no symbols, no watermarks.

## 后续替换

背景建议横构图、为文字保留安静的暗部。图集使用等宽人物列，各人物头肩不要跨列；column 从 0 开始。大图会影响首屏加载，可在后期统一压缩为 WebP，并同步修改配置路径。
