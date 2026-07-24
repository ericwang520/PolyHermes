## AWS deployment

先备份数据库。以下命令均为单行，避免 shell 换行导致参数被拆开。

```bash
cd /home/ec2-user/polyhermes && mkdir -p backups && docker exec polyhermes-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction polyhermes' > "backups/before-paper-settlement-$(date +%Y%m%d-%H%M%S).sql"
```

拉取 fork 分支并构建镜像：

```bash
cd /home/ec2-user/polyhermes-src && git fetch origin && git switch feature/deposit-wallet-poly1271 && git pull --ff-only
```

```bash
cd /home/ec2-user/polyhermes-src && docker build --no-cache --build-arg BUILD_IN_DOCKER=true --build-arg VERSION=2.5.1-paper-settlement --build-arg GIT_TAG="$(git rev-parse --short HEAD)" --build-arg GITHUB_REPO_URL=https://github.com/ericwang520/PolyHermes -t polyhermes-deposit:paper-settlement .
```

切换现有 Compose 的应用镜像，不删除 MySQL volume：

```bash
cd /home/ec2-user/polyhermes && sed -i 's|image: .*polyhermes.*|image: polyhermes-deposit:paper-settlement|' docker-compose.prod.yml && docker compose -f docker-compose.prod.yml up -d --no-deps --force-recreate app
```

验证迁移、版本和健康状态：

```bash
cd /home/ec2-user/polyhermes && docker compose -f docker-compose.prod.yml ps && docker compose -f docker-compose.prod.yml logs --tail=200 app
```

预期 Flyway 执行 `V43__add_copy_simulation.sql`，应用最终进入 healthy。
