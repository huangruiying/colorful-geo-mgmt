# 运行时环境记忆

用于接口回归、联调测试、数据库校验时快速定位测试环境连接方式。

## 团油测试环境连接规则

- IDE 启动参数若为 `-Dapollo.cluster=TEST01`，默认匹配团油 `01` 环境。
- 本项目路由相关 SQL 默认库名按 Mapper 推断为 `czb_payment`，例如 `route_trade_rule_detail` 使用了 `czb_payment.route_trade_rule_detail`。
- 数据库密码属于敏感信息，不写入长期 memory；需要时由用户在当前会话提供，或通过安全凭据渠道获取。

## MySQL 环境索引

| 环境 | host | port | user | 默认库名 |
|---|---|---:|---|---|
| 01 | `10.10.11.71` | 3306 | `test_dev01` | `czb_payment` |
| 02 | `10.10.11.122` | 3306 | `test_dev02` | `czb_payment` |
| 03 | `10.10.11.133` | 3306 | `test_dev03` | `czb_payment` |
| 04 | `10.10.11.76` | 3306 | `test_dev04` | `czb_payment` |
| 05 | `10.10.11.110` | 3306 | `test_dev05` | `czb_payment` |
| 06 | `10.10.11.22` | 3306 | `test_dev06` | `czb_payment` |
| 07 | `10.10.11.136` | 3306 | `test_dev07` | `czb_payment` |

## Redis 环境索引

Redis 密码不写入长期 memory。

| 环境 | host | port |
|---|---|---:|
| 01 | `10.10.18.4` | 6379 |
| 02 | `10.10.18.2` | 6379 |
| 03 | `10.10.18.5` | 6379 |

