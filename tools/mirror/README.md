# 镜像仓库（灾难备份）

| | |
|---|---|
| 镜像仓库 | `https://github.com/ysb-discowave/HBMZZ-MOD-mirror`（私有） |
| 方向 | 主仓库 → 镜像，**单向**，没有反向 |
| 触发 | **只在仓库所有者明确同意后**，由人执行 `--push --yes` |

## 它是干什么的

主仓库出事时用的那一份：被删、被强推、被协作者批量删文件、被一次错误的
`git reset --hard` 覆盖。它存的是**完整历史**——所有分支、所有标签、每一条提交，
不是一份快照。

所以它必须比主仓库更「难动」：

- **不邀请任何协作者。** 主仓库有写权限的人，对镜像没有任何权限——这正是它的价值。
- **不接受直接提交。** 同步只走 `tools/mirror/mirror.py`，别的来源一律不该出现。
- **不自动同步。** 没有授权就不推，宁可落后，也不要让镜像变成第二个被污染的地方。

## 什么时候同步

**只在所有者明确同意后。** 这是约定，不是建议：

1. 完成一项任务后，跑一次不带参数的 `mirror.py`，把差异交给所有者看；
2. 所有者说「同步」才执行 `--push --yes`；
3. 没说就保持现状，等下一步指示。

不带 `--yes` 时脚本**只打印、不推送**；带 `--push` 但不带 `--yes` 会被直接拒绝——
这是防止「顺手跑一下」的那种事故。

## 命令

```bash
python tools/mirror/mirror.py                 # 只看：领先多少提交、缺哪些标签、改了哪些目录
python tools/mirror/mirror.py --push --yes    # 真推（需所有者同意）
python tools/mirror/mirror.py --push --yes --prune   # 顺带删掉镜像上主仓库已经没有的分支
```

推送的是 `refs/remotes/origin/*`，不是本机分支：本机落后于远端时，镜像拿到的
仍然是最新的远端状态，不会因为「忘了 pull」而备份出一份旧代码。推完脚本会
复核镜像的 `master` 是否等于主仓库的 `master`，不一致就报错。

## 主仓库没了，怎么恢复

```bash
git clone https://github.com/ysb-discowave/HBMZZ-MOD-mirror.git HBMZZ-MOD
cd HBMZZ-MOD
git remote set-url origin <新的主仓库地址>
git push origin --all
git push origin --tags
```

站点（`docs/`）在仓库里，所以 Pages 换个仓库重新指一下就能恢复。

## 它不备份什么

- **Release 资产**（`hbmzz-x.y.z.jar`、源码包）：这些是 GitHub 的附件，不在 git 里。
  重新发版可以重建，但历史版本的字节不会一模一样——要留档就自己下一份存着。
- **Issues / PR / 讨论**：另一套数据，需要单独导出。
