# Upstream sources / 上游来源

[Documentation / 文档](README.en.md) · [Retained notices / 保留声明](../THIRD_PARTY_NOTICES.md)

| Project | Pinned reference | Use / 用途 |
|---|---|---|
| [EhViewer](https://github.com/FooIbar/EhViewer) | `b9729e94ff3f282321fb95a8db22735839102719` | Library cards, categories and list interactions / 图库与分类交互参考 |
| [Mihon](https://github.com/mihonapp/mihon) | `f52d890e7f8a3c418ddab41f41d4b577bce0dc06` | Chapter/reader/local metadata behavior; reader image-library fork / 章节、阅读与本地元数据参考 |
| [manga-translator-android](https://github.com/jedzqer/manga-translator-android) | Seg code: `809452a4a6b10f520559ad6439e3a85d8ae4c680`; later workflow reference: `d2fed1a849d1dbff697d9789952a4222372bfb3a` | Adapted Seg execution code and translation-protocol research / Seg 执行代码适配与翻译协议参考 |

Research references are not wholesale inclusion of upstream applications. LM-Reader implements its own local indexing, identities, typed workflow editor/executor, queues, persistence and Android integration. Adapted files preserve their original headers; dependencies/models retain individual notices.

参考交互不等于复制整套上游应用。实际复用的代码、图片引擎与原生依赖保留各自声明；本应用的本地索引、类型化工作流、任务和数据恢复由本项目实现。

Translation-native source provenance is pinned separately in [translation_sources.lock.json](../tools/translation_sources.lock.json). Vision sources/model hashes are in [fetch_vision_models.py](../tools/fetch_vision_models.py). Seg weight distribution evidence and the project's own license selection remain release tasks; do not infer model terms from code licenses.
