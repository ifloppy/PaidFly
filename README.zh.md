[**English**](https://github.com/coderxi1/PaidFly/blob/main/README.md)

# PaidFly

**PaidFly** 是一个 Minecraft（Paper）服务器插件，允许玩家消耗游戏币或经验值进行飞行！

![banner](https://github.com/user-attachments/assets/a36e817f-58ae-4890-9f6c-373ac6a22fca)

## 功能

- 支持使用游戏币/经验值进行付费飞行
- 支持聊天、标题栏、ActionBar 和声音提示
- 支持倒计时提醒
- 飞行期间累积费用，在飞行结束时统一结算
- 每个计费周期只查询余额，不执行扣款

## 安装

1. 从[插件发布页](https://github.com/coderxi1/PaidFly/releases)下载最新版本**`PaidFly.jar`**（如果服务器已有`kotlin-stdlib`，则可以使用`PaidFly-kotlin.jar`）
2. 将插件放入服务器的`plugins/`文件夹
3. 为默认用户组设置`paidfly.fly`权限以允许玩家使用
4. 如果使用经验值飞行，请确保服务器安装了`Vault`经济插件。

## 配置

```yaml
Main:
  PayType: Money        # 支付类型
  PayInterval: 3s       # 支付间隔
  PayCost: 0.5          # 每个计费周期的费用
  AutoOffThreshold: 0.5 # 余额低于此值时关闭飞行
```

玩家飞行期间，PaidFly 会在内存中累积费用。每个计费周期只检查当前余额是否能够覆盖“已累积费用 + 自动关闭阈值”，不会调用经济插件扣款；玩家关闭飞行、退出服务器、重载插件或服务器关闭时，累积费用只结算一次。未完成的计费周期不会收费。

## 命令

所有命令以`/paidfly`开头，别名为`/fly`：

| 命令 | 权限 | 说明 |
|---------|-------------|-------------|
| `/fly` | `paidfly.fly` | 等同于`/fly toggle` |
| `/fly on/off/toggle` | `paidfly.fly` | 切换飞行状态 |
| `/fly on/off/toggle [player]` | `paidfly.others` | 为指定玩家切换飞行状态，需要`paidfly.others`权限 |
| `/fly help` | `paidfly.use` | 显示插件帮助（根据权限显示不同内容） |
| `/fly reload` | `paidfly.reload` | 重载插件配置 |
