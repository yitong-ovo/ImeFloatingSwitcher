# Commit 规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/)。

## 格式

```text
<type>(<scope>): <description>
```

- `scope` 可选，使用受影响的模块名，例如 `ime`、`overlay`、`shizuku`、`parser`、`build`。
- `description` 使用简洁、明确的中文祈使句，不加句号，单次提交只做一件事。
- 需要补充时，在标题后空一行写正文；关联问题或其他元信息放在提交页脚（footer）。

## 类型

- `feat`：新增功能
- `fix`：修复问题
- `refactor`：重构，不改变行为
- `perf`：性能优化
- `test`：测试变更
- `docs`：文档变更
- `build`：构建系统或依赖变更
- `ci`：持续集成配置变更
- `chore`：其他维护工作
- `revert`：回滚提交

破坏性变更在 `type` 后加 `!`，并在 footer 说明 `BREAKING CHANGE`。

## 示例

```text
feat(overlay): 新增可拖动悬浮按钮
fix(parser): 保留未知输入法状态
test(ime): 覆盖切换失败场景
docs: 更新使用说明
build: 更新 Android Gradle 插件
```
