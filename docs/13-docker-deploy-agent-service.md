# Docker 部署 Agent 服务（写给 Android / Java 开发者）

## 0. 这份文档怎么用

系列第五篇，对应路线图学习优先级 #5「Docker / Redis / Postgres」、规划里的「Docker Compose 部署 Agent 服务」。

**这篇和前四篇性质又不一样：它是纯工程 / 运维**，Docker、Redis、Postgres 都是成熟通用技术。所以：

- 框架往回靠到 **“看懂配置”**（像第一、二篇的阅读理解）——Dockerfile 和 compose 文件你主要是**读和改**，不是从零设计。
- **你的 Gradle / Android 工程经验直接迁移**，这是“你已经会一半”的一篇。

一句话剧透，把整篇串起来：

> 前四篇造出了一个**能在你机器上跑的 Agent 脚本**；这篇把它打包成 **`docker compose up` 一条命令、别人也能一键跑起来的系统**。

依旧的配方：Java / Android 类比、难点上代码、一段逐行精读、速查表 + 参考链接。难度按你选的**入门一键部署**档，但**异步 worker + 队列**会展开讲（这是 Agent 部署最不同于普通后端的地方）。

带 ★ 的是重点：
- ★★ **Part 5 看懂 docker-compose.yml** —— 本文锚点。
- ★ **Part 3 看懂 Dockerfile**、**Part 6 异步 worker + 队列**。

---

# Part 1 · 为什么 Agent 服务要容器化

普通脚本在你机器上能跑，换台机器常常就崩——Python 版本不对、少装个依赖、环境变量没配。Docker 解决的就是这个“**在我机器上是好的啊**”问题：**把代码 + 依赖 + 运行环境一起打包**，到哪都一样。

对 Agent 服务，容器化尤其值，有四个具体理由：

1. **环境可复现**：Agent 依赖一堆 Python 包、可能还要系统工具（浏览器、代码执行环境）。Docker 把这些钉死，谁拉下来都一样。
2. **工具执行要隔离**：Agent 常要执行代码、读写文件、跑命令（路线图原话：“Agent 系统常涉及代码执行、文件读写、浏览器操作，所以运行环境隔离很重要”）。容器是个**隔离沙箱**，把这些动作的影响范围圈住。
3. **慢任务要异步**：一次 Agent 执行要调好几次模型，可能几十秒甚至几分钟，不能堵在 HTTP 请求里。需要后台 **worker + 队列**（Part 6 展开）。
4. **一键给别人跑**：你的作品集项目，面试官 `docker compose up` 就能跑起来——不用配环境，这本身就是加分项。

---

# Part 2 · 核心概念速通（全程 Gradle / APK 类比）

四个词搞定 Docker 的地基：

| Docker 概念 | 是什么 | Android / Java 类比 |
|---|---|---|
| **镜像 image** | 打包好的、不可变的制品（代码+依赖+运行时）| **打好的 APK / AAR**：一个封装好、拿了就能跑的产物 |
| **容器 container** | 镜像的一个**运行实例** | 跑起来的 App 进程 / 一个**隔离的模拟器实例** |
| **Dockerfile** | 怎么构建镜像的“配方” | **build.gradle**：定义怎么把工程构建成产物 |
| **镜像仓库 registry**（Docker Hub）| 镜像存放/分发的地方 | **Maven Central**：依赖/产物从这里拉 |

关键区分两个：**image 是“制品”，container 是“制品跑起来的实例”**。一个 image 可以起多个 container（就像一个 APK 装到多台设备上跑）。

`postgres`、`redis` 这种基础设施，**别人早打好镜像放在 Docker Hub 了**，你直接拉来用（`image: postgres:16`），不用自己写 Dockerfile——就像你 Gradle 里直接依赖一个现成的库，而不是自己实现。

---

# Part 3 · 看懂一个 Dockerfile ★

下面是给第一篇那个 FastAPI Agent 服务写的 Dockerfile，逐行精读：

```dockerfile
FROM python:3.12-slim          # 基础镜像:一个装好 Python 3.12 的精简 Linux(类比:选 base/依赖来源)
WORKDIR /app                   # 容器内的工作目录,后续命令都在这里执行

COPY requirements.txt .        # ① 先只拷"依赖清单"(为了利用缓存,见下)
RUN pip install --no-cache-dir -r requirements.txt   # 装依赖(requirements.txt ≈ build.gradle 的 deps)

COPY . .                       # ② 再拷项目代码

ENV PYTHONUNBUFFERED=1         # 让日志实时输出(不缓冲),容器里看 log 才及时
EXPOSE 8000                    # 声明容器监听 8000(主要是文档性质)

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]  # 容器启动时执行的命令
```

几个要点（看 AI 生成的 Dockerfile 时对号入座）：

- **`FROM` 选基础镜像**：`-slim` 是精简版，镜像更小。约等于你选一个轻量的 base 环境。
- **为什么先拷 `requirements.txt`、装完依赖、再拷代码（①②分开）**：Docker 是**分层缓存**的——只要 `requirements.txt` 没变，装依赖这层就直接复用缓存，改代码不会重装一遍依赖。**这跟 Gradle 增量构建复用缓存是一个思路**，能大幅加快重建。
- **`CMD` 是启动命令**：就是第一篇里那行 `uvicorn app.main:app`（回扣 doc1）。
- **⚠️ 必须 `--host 0.0.0.0`，不能 `127.0.0.1`**：容器里绑 `127.0.0.1` 只有容器自己能访问，外面连不上。这是新手最常踩的坑——**容器内服务一律绑 `0.0.0.0`**。

构建和运行：

```bash
docker build -t my-agent .            # 按 Dockerfile 构建镜像,命名(tag)为 my-agent
docker run -p 8000:8000 --env-file .env my-agent   # 跑起来
```

- `-p 8000:8000`：端口映射，`宿主机端口:容器端口`，这样你本机访问 8000 能打到容器里。
- `--env-file .env`：注入环境变量（下一节）。

> 顺带：建个 `.dockerignore`（用法和 `.gitignore` 一样），把 `.venv/`、`__pycache__/`、`.env` 等排除掉，别打进镜像——又小又安全。

---

# Part 4 · 别把密钥打进镜像：环境变量与 secrets

回扣第二篇：调用模型要 `ANTHROPIC_API_KEY`。**千万别这么写：**

```dockerfile
ENV ANTHROPIC_API_KEY=sk-ant-xxxxx   # ❌ 密钥被烤进镜像,谁拿到镜像谁就拿到你的 key
```

镜像是会被分发的，写死密钥等于公开泄露。正确做法：**密钥在“运行时”注入，不进镜像**。

- 本地放一个 `.env` 文件（**务必 gitignore，也别打进镜像**）：

  ```
  ANTHROPIC_API_KEY=sk-ant-xxxxx
  DATABASE_URL=postgresql://agent:secret@postgres:5432/agentdb
  REDIS_URL=redis://redis:6379/0
  ```

- 运行时用 `--env-file .env`（或 compose 的 `env_file:`）把它喂进容器。

**Android 类比**：这跟你**不把签名密钥 / API key 提交进 git、而是放 `local.properties` 或 CI 的 secret 里**是同一个原则——**配置和密钥与代码分离**，按环境注入。

---

# Part 5 · 看懂 docker-compose.yml ★★

一个真实 Agent 服务不止一个进程：**API + 后台 worker + Postgres + Redis**。挨个 `docker run` 太累，`docker-compose.yml` 把它们**声明在一起、一键起停**。这是本文最该读懂的文件：

```yaml
services:
  app:                         # ① FastAPI Agent API(对外的 HTTP 服务)
    build: .                   # 用当前目录的 Dockerfile 构建镜像
    ports:
      - "8000:8000"            # 宿主机:容器 端口映射
    env_file: .env             # 注入 ANTHROPIC_API_KEY 等密钥
    environment:
      - DATABASE_URL=postgresql://agent:secret@postgres:5432/agentdb
      - REDIS_URL=redis://redis:6379/0
    depends_on: [postgres, redis]   # 先起 postgres / redis,再起 app

  worker:                      # ② 后台 worker:跑慢的 Agent 任务(见 Part 6)
    build: .                   # 和 app 用同一个镜像
    command: rq worker --url redis://redis:6379/0   # 但启动命令不同:不是 uvicorn,是消费队列
    env_file: .env
    environment:
      - DATABASE_URL=postgresql://agent:secret@postgres:5432/agentdb
    depends_on: [postgres, redis]

  postgres:                    # ③ 数据库:存 trace / eval / task(接替 doc3 的 SQLite)
    image: postgres:16         # 直接用 Docker Hub 现成镜像,不用自己写 Dockerfile
    environment:
      - POSTGRES_USER=agent
      - POSTGRES_PASSWORD=secret
      - POSTGRES_DB=agentdb
    volumes:
      - pgdata:/var/lib/postgresql/data   # 持久化:容器删了,数据还在

  redis:                       # ④ 队列 + 缓存
    image: redis:7

volumes:
  pgdata:                      # 具名数据卷,给 Postgres 存数据
```

逐项看清（这几条是 compose 的核心，看懂就通了）：

- **每个 service 是一个容器**：`app`、`worker`、`postgres`、`redis` 四个。
- **`build: .` vs `image: postgres:16`**：自己的代码用 `build`（按 Dockerfile 构建）；现成基础设施直接用 `image`（从 Docker Hub 拉）。
- **🔑 服务名就是主机名**：`app` 连数据库用的是 `postgres:5432`、连缓存用 `redis:6379`——compose 自动建了一个内网，**容器之间用“服务名”互相访问**。所以 `DATABASE_URL` 里是 `@postgres:5432` 而不是 `localhost`。（用 `localhost` 互连是新手大坑：在容器里 `localhost` 指的是容器自己。）
- **`app` 和 `worker` 共用一个镜像，只是 `command` 不同**：`app` 跑 uvicorn（Dockerfile 里的默认 `CMD`），`worker` 用 `command:` 覆盖成跑队列消费者。同一份代码、两种角色。
- **`volumes` 让数据持久化**：容器默认是“用完即弃”的，删了重建数据就没了。Postgres 必须挂一个卷（`pgdata`），数据才能活过容器重启。
- **`depends_on` 只管启动顺序，不保证“已就绪”**：它确保 postgres 先**启动**，但不保证它已经**能连**。所以应用连数据库时要带重试（或配 healthcheck）——这是个常见坑。

> **Android 类比**：compose 有点像一份**声明式的“服务清单 + 接线图”**——声明要跑哪几个服务、各自怎么配、谁依赖谁、彼此怎么找到对方。比起手动一个个 `docker run`，它把整套系统的编排写成了一个文件。

---

# Part 6 · 为什么 Agent 要异步：worker + 队列 ★

这是 Agent 部署**最不同于普通 CRUD 后端**的地方。

## 问题：Agent 任务很慢

一次 Agent 执行要在循环里调好几次模型、跑好几个工具（回扣第二篇的 agentic loop），**动辄几十秒甚至几分钟**。如果你把它**同步**写在 HTTP 接口里：

```python
@app.post("/run")
def run(req):
    return run_agent(req.input)   # ❌ 这一卡就是几分钟
```

会出两个问题：**(1)** HTTP 请求一直挂着 → 客户端/网关超时；**(2)** Web 进程被长任务占住 → 扛不住并发。

## 解法：入队 → 后台 worker 执行 → 轮询状态

把“接收请求”和“真正干活”拆开：

```
① 客户端 POST /tasks        → API 把任务【入队】到 Redis,立刻返回 {task_id, status: "queued"}
② 后台 worker 进程          → 从队列取出任务,跑慢的 run_agent(),把 trace 写进 Postgres,更新状态为 done
③ 客户端 GET /tasks/{id}    → 轮询查状态/拿结果
```

**Android 类比（非常贴切）**：这就是 **WorkManager / JobScheduler**——你 `enqueue` 一个后台任务，立刻返回；一个后台 worker 慢慢跑；你通过 `WorkInfo` 观察状态。Agent 的 worker+队列是同一个模式，只是跨进程/跨容器。

> **这下第一篇的 Task API 通了**：doc1 那个“创建任务 / 查任务 / 更新状态”、带 `status` 字段的设计，正是为这种异步执行准备的——`status` 在 `queued → running → done` 之间流转，就是给客户端轮询用的。

代码长这样（用 RQ = Redis Queue，最简单的一种；Celery 是更全功能的另一选择）：

```python
# app/queue.py —— 建一个连到 Redis 的队列
import os
from redis import Redis
from rq import Queue
queue = Queue(connection=Redis.from_url(os.environ["REDIS_URL"]))
```

```python
# app/main.py —— HTTP 侧:入队即返回,不阻塞
@app.post("/tasks")
def create_task(req: TaskIn):
    job = queue.enqueue("app.jobs.run_agent_job", req.input)   # 入队,马上返回
    return {"task_id": job.id, "status": "queued"}

@app.get("/tasks/{task_id}")
def get_task(task_id: str):
    job = queue.fetch_job(task_id)
    return {"task_id": task_id, "status": job.get_status(), "result": job.result}
```

```python
# app/jobs.py —— worker 进程真正执行的函数
def run_agent_job(user_input: str) -> str:
    trace = run_agent(user_input)   # 慢任务(第二/三篇),可能几十秒
    save_trace(trace)               # 写 Postgres(第三篇的存储,从 SQLite 换成 PG)
    return trace.trace_id
```

而 worker 进程，就是 compose 里那个 `worker` 服务跑的 `rq worker`——它盯着队列，一有任务就拿来执行。**API 容器和 worker 容器跑的是同一份代码、同一个镜像，只是入口命令不同**（回扣 Part 5）。

---

# Part 7 · Postgres 与 Redis 各扮演什么角色

| | 是什么 | 在 Agent 系统里干嘛 | 类比 |
|---|---|---|---|
| **Postgres** | 关系型数据库（服务端、网络访问、多连接）| 持久存 **trace / eval / task** 等状态 | doc1 说过 SQLAlchemy ≈ 服务端 ORM；它就是那个“服务端数据库” |
| **Redis** | 内存键值存储（极快）| ① 任务**队列**（worker 用）② **缓存** | 一个多进程共享的、超快的“内存版 HashMap 服务” |

**关于 Postgres 和第三篇 SQLite 的关系**：第三篇用 SQLite 存 trace，是因为它**单文件、零配置**，做开发/单机/入门最省事。但 SQLite 不适合多进程并发写（你现在有 app + worker 两个进程同时要写）。**Postgres 是它的“生产版”**：独立服务、支持多连接并发、网络访问。代码层面如果用 SQLAlchemy（doc1 提过），从 SQLite 切到 Postgres 基本只改一个连接串（`DATABASE_URL`）。

**Redis 为什么也单独一个容器**：它是 worker 队列的“中转站”——API 把任务塞进 Redis，worker 从 Redis 取。两个进程通过它解耦。顺带也能当缓存（比如缓存重复的模型请求，回扣 doc2 的 prompt caching 思路，只是这是你自己在应用层缓存）。

---

# Part 8 · 一键启动 & 常用命令速查

```bash
docker compose up -d            # 后台启动全部服务(app/worker/postgres/redis)
docker compose up --build       # 改了代码/Dockerfile 后,重新构建并启动
docker compose ps               # 看哪些服务在跑、端口映射
docker compose logs -f app      # 实时跟踪 app 的日志(-f = follow)
docker compose logs -f worker   # 看 worker 在跑什么任务
docker compose exec app bash    # 钻进 app 容器里执行命令(调试用)
docker compose down             # 停掉并删除所有容器(数据卷保留)
docker compose down -v          # ⚠️ 连数据卷一起删 —— 会清空数据库,慎用
```

对你这个项目，日常就是：改完代码 → `docker compose up --build` → 看 `logs` → 出问题 `exec ... bash` 进去查。

---

# Part 9 · Kubernetes：是什么、何时才轮到它

路线图把 K8s 放在很靠后（#9），明确说**先理解概念即可**。这里给你定个位，别被它吓到：

- **compose 是“单机编排”**：在一台机器上把几个容器接好、一起起停。**学习和作品集阶段，compose 完全够用。**
- **Kubernetes（K8s）是“集群编排”**：当你要把一堆容器**跨多台机器**跑、还要自动扩缩容、自愈（挂了自动重启）、滚动更新（不停机升级）时，才用它。

需要认识的几个概念（路线图列的）：

| 概念 | 一句话 |
|---|---|
| **Pod** | 最小部署单位，一个或一组紧挨着的容器 |
| **Deployment** | 声明“这个服务要跑几个副本、怎么滚动更新” |
| **Service** | 给一组 Pod 一个稳定的访问入口（带负载均衡）|
| **ConfigMap** | 配置（非密钥）|
| **Secret** | 密钥（API key、密码）|

**Android 类比（粗略）**：compose 像在**一台机器**上编排几个服务；K8s 像一个**集群调度系统**，管一堆机器上的一堆容器——有点“容器界的操作系统/调度器”的味道。**现在不用学深，知道“它解决多机/弹性/高可用，compose 扛不住了才上”就行。**

---

# Part 10 · 速查表 & 易错点

## 读部署配置的“三步速读法”

1. **看 Dockerfile**：`FROM` 什么基础镜像、装了哪些依赖、`CMD` 怎么启动 → 这个服务怎么打包、怎么跑。
2. **看 compose 的 services**：有哪几个容器、哪些是自己 `build` 哪些用现成 `image`、谁连谁（看 `environment` 里的服务名）→ 整个系统的拓扑。
3. **看 worker / 队列**：有没有独立的 `worker` 服务、`command` 是不是在消费队列 → 慢任务是不是异步跑的。

## 最容易踩的坑

- **容器里服务绑 `127.0.0.1`** → 外面连不上，必须 `--host 0.0.0.0`。
- **把密钥 `ENV` 进 Dockerfile / 把 `.env` 提交或打进镜像** → 泄露。密钥运行时注入。
- **Postgres 不挂 `volume`** → 容器一删/重建，数据全没。有状态服务必须挂卷。
- **容器间用 `localhost` 互连** → 错，要用**服务名**（`postgres`、`redis`）。
- **`depends_on` 当成“等就绪”** → 它只保证启动顺序，不保证数据库已能连，应用要带重试。
- **在 HTTP 请求里同步跑长 Agent 任务** → 超时。走 worker + 队列（Part 6）。
- **镜像越堆越大 / 重建很慢** → 用 `-slim` 基础镜像、加 `.dockerignore`、依赖清单先拷后装利用缓存。

---

## 参考链接 / 延伸阅读

> 官方文档为主，内容随版本更新；**失效时按标题/项目名搜索即可**。

**Docker**
- 官方文档：https://docs.docker.com/
- 入门教程 Get Started：https://docs.docker.com/get-started/
- Dockerfile 参考：https://docs.docker.com/reference/dockerfile/
- Compose：https://docs.docker.com/compose/

**基础设施镜像（Docker Hub）**
- Postgres 官方镜像：https://hub.docker.com/_/postgres
- Redis 官方镜像：https://hub.docker.com/_/redis

**异步队列**
- RQ（Redis Queue，最简单）：https://python-rq.org/
- Celery（功能更全）：https://docs.celeryq.dev/

**Kubernetes（概念了解即可）**
- 基础概念：https://kubernetes.io/docs/concepts/

---

## 下一步建议

读完这篇，最有效的巩固是 **给前四篇攒下来的 Agent 服务写一个 `Dockerfile` + `docker-compose.yml`**：把 app、worker、Postgres、Redis 接起来，`docker compose up` 跑通，再用 `POST /tasks` 异步提交一个 Agent 任务、轮询拿结果、去 Postgres 里看 trace。跑通这一套，你就完成了从“脚本”到“可部署系统”的跨越。

到这里，**五件套闭环了**：

> **FastAPI（造服务）→ LLM/工具（让模型干活）→ Trace（记录）→ Eval（评判）→ Docker（打包部署）**

这正好拼成路线图的作品集项目 **AgentOps Mini Platform** 的后端形态（FastAPI + Postgres + Redis + Docker Compose）。再往后（路线图 #6 起）是 **LangGraph / OpenAI Agents SDK**（更专业的 Agent 编排框架）、**React 内部平台**（给这套加个前端界面）、**MCP**、以及 **Kubernetes** 等进阶——但核心的工程闭环，你已经走完一圈了。
