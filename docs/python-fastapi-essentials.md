# Python + FastAPI 阅读理解指南（写给 Android / Java 开发者）

## 0. 这份文档怎么用

你的目标不是"从零精通 Python"，而是 **能看懂 AI 写的 FastAPI 代码，并能判断它写得对不对、改得动**。

所以这份文档的取舍是：

- 跳过"如何安装 Python""print 怎么用"这种网上一搜一大把的内容。
- 重点讲 **从 Java 视角看会觉得别扭、陌生、容易看错** 的地方。
- 凡是有合适的 Java / Android 生态类比（Retrofit、OkHttp、Gson、Dagger 等）就用，没有就直接讲清楚，不强行类比。
- 带 ★ 的章节是"看不懂 AI 代码"的高频元凶，建议重点看：
  - ★★★ 装饰器 Decorator
  - ★★ async/await、Pydantic 模型、依赖注入 Depends
  - ★ 类型标注、推导式、`*args/**kwargs`

读完之后，配合最后的 **Part 4 逐行精读** 和 **Part 5 速查表**，再看 AI 生成的代码应该不会有"这行到底在干嘛"的卡顿。

> 约定：本文 Python 代码假设版本 **3.10+**，FastAPI 用的是 **Pydantic v2**（2024 年后的主流版本）。

---

# Part 1 · Python 语法速通

## 1.1 缩进即代码块（没有大括号）

Java 用 `{}` 划分代码块，Python 用 **缩进**（通常 4 个空格）。冒号 `:` 表示"下面要进入一个块"。

```python
def check(age):
    if age >= 18:
        return "adult"
    else:
        return "minor"
```

对应 Java：

```java
String check(int age) {
    if (age >= 18) {
        return "adult";
    } else {
        return "minor";
    }
}
```

要点：

- **缩进是语法的一部分**，缩进错了就是语法错误，不是风格问题。
- 行尾**不写分号**。
- 函数、if、for、while、class、with、try 后面都跟 `:`，然后换行缩进。

## 1.2 变量与动态类型

Python 变量声明不写类型，直接赋值。一个变量可以先后指向不同类型的值（动态类型）。

```python
x = 10          # 现在是 int
x = "hello"     # 完全合法，现在是 str
```

可以理解成：Python 里所有变量都像 Java 的 `Object` 引用，但赋值时不需要强转，运行时才知道真实类型。

> 这也是为什么后面"类型标注"很重要——它把丢失的类型信息**作为提示**补回来。

## 1.3 Type Hints 类型标注 ★

这是读懂 FastAPI 代码的地基，一定要看。

写法：参数用 `名字: 类型`，返回值用 `-> 类型`。

```python
def greet(name: str, age: int) -> str:
    return f"{name} is {age}"
```

对应 Java：`String greet(String name, int age)`。

**和 Java 最关键的区别**：Python 的类型标注 **运行时不强制**。它主要给 IDE、类型检查工具（mypy）和框架（FastAPI）看。下面这行不会报错，照样能跑：

```python
greet(123, "abc")   # 类型"不对"，但 Python 不拦你
```

> 例外：**FastAPI 会真的拿这些标注去校验请求数据**。这是 FastAPI 的核心魔法之一——它读你写的类型标注，自动帮你解析和校验 HTTP 参数。后面 Part 3 会展开。

常见类型写法对照：

| Python | 含义 | Java 近似 |
|---|---|---|
| `int` `float` `str` `bool` | 基本类型 | `int/long` `double` `String` `boolean` |
| `list[int]` | 整数列表 | `List<Integer>` |
| `dict[str, int]` | 字符串→整数的字典 | `Map<String, Integer>` |
| `str \| None` | 字符串或空 | `@Nullable String` / `Optional<String>` |
| `Optional[str]` | 同上（旧写法） | 同上 |
| `Any` | 任意类型 | `Object` |

`str | None` 和 `Optional[str]` 完全等价，AI 两种都可能写：

```python
def find_user(uid: int) -> str | None:   # 可能返回字符串，也可能返回 None
    ...

# 等价于
from typing import Optional
def find_user(uid: int) -> Optional[str]:
    ...
```

## 1.4 基本数据结构：list / dict / tuple / set

| Python | 字面量 | Java 近似 | 说明 |
|---|---|---|---|
| `list` | `[1, 2, 3]` | `ArrayList` | 有序、可变、可重复 |
| `dict` | `{"a": 1, "b": 2}` | `HashMap` | 键值对 |
| `tuple` | `(1, 2, 3)` | （无直接对应）| 有序、**不可变** |
| `set` | `{1, 2, 3}` | `HashSet` | 无序、去重 |

```python
nums = [1, 2, 3]
nums.append(4)            # [1, 2, 3, 4]
print(nums[0])            # 1
print(nums[-1])           # 4，负数索引 = 倒数，Java 没有这个

user = {"name": "Tom", "age": 18}
print(user["name"])       # Tom
user["email"] = "x@y.com" # 新增键

point = (120.5, 30.2)     # tuple，不能改元素
x, y = point              # 解包：x=120.5, y=30.2（很常见）
```

注意两个 Java 没有的好用特性：

- **负索引**：`nums[-1]` 是最后一个元素。
- **解包(unpacking)**：`x, y = point` 一次把元组拆给多个变量。函数返回多个值时常这么用。

## 1.5 函数：默认参数、关键字参数、`*args` / `**kwargs` ★

### 默认参数

```python
def create(name: str, active: bool = True):
    ...
```

`active` 有默认值，调用时可省略。Java 里你得靠方法重载实现，Python 一行搞定。

### 关键字参数（按名字传参）

调用时可以写参数名，**顺序就无所谓了**，可读性很好：

```python
create("Tom")                       # active 用默认值 True
create("Tom", active=False)         # 显式指定
create(name="Tom", active=False)    # 全部按名字传
```

AI 写的代码里大量用 `参数名=值` 这种形式，看到不要疑惑，它就是普通传参。

### `*args` 和 `**kwargs`

这两个符号第一次见一定懵，其实很简单：

- `*args`：把"多余的位置参数"收集成一个 **tuple**。类似 Java 的可变参数 `Object... args`。
- `**kwargs`：把"多余的关键字参数"收集成一个 **dict**。Java 没有直接对应。

```python
def demo(*args, **kwargs):
    print(args)     # tuple
    print(kwargs)   # dict

demo(1, 2, 3, name="Tom", age=18)
# args   = (1, 2, 3)
# kwargs = {"name": "Tom", "age": 18}
```

反过来，`*` 和 `**` 也能"展开"：

```python
nums = [1, 2, 3]
print(*nums)          # 等价于 print(1, 2, 3)

config = {"name": "Tom", "age": 18}
create(**config)      # 等价于 create(name="Tom", age=18)
```

> 记住一句话：**定义函数时 `*`/`**` 是"收集"，调用函数时 `*`/`**` 是"展开"**。

## 1.6 f-string 字符串格式化

字符串前加 `f`，里面用 `{}` 直接嵌变量或表达式：

```python
name = "Tom"
age = 18
msg = f"{name} is {age}, next year {age + 1}"
# "Tom is 18, next year 19"
```

约等于 Java 的 `String.format("%s is %d", name, age)`，但更直观。AI 几乎只用 f-string。

## 1.7 None 与 Optional

`None` 就是 Java 的 `null`。判空用 `is`：

```python
if user is None:
    ...
if user is not None:
    ...
```

> 注意：判空习惯用 `is None` 而不是 `== None`。看到 `is`/`is not` 是 Python 风格，别当成 bug。

## 1.8 推导式 Comprehension ★

这是 Python 极有特色、AI 极爱用、初见极易懵的语法。本质就是 **Java Stream 的 `map`/`filter` 的紧凑写法**。

### 列表推导式

```python
squares = [x * x for x in range(5)]
# [0, 1, 4, 9, 16]
```

对应 Java Stream：

```java
List<Integer> squares = IntStream.range(0, 5)
    .map(x -> x * x).boxed().collect(toList());
```

读法（从左往右翻译）：`[表达式 for 变量 in 集合]` → "对集合里每个变量，算出表达式，收集成列表"。

### 带过滤

```python
evens = [x for x in range(10) if x % 2 == 0]
# [0, 2, 4, 6, 8]
```

→ `.filter(x -> x % 2 == 0)` 后再收集。

### 字典推导式

```python
users = [("Tom", 18), ("Amy", 20)]
age_map = {name: age for name, age in users}
# {"Tom": 18, "Amy": 20}
```

只要记住 **`[... for ... in ...]` 就是"遍历 + 变换 + 收集"**，再长也能拆开读。

## 1.9 类、self、`__init__`

```python
class User:
    def __init__(self, name: str, age: int):
        self.name = name
        self.age = age

    def greet(self) -> str:
        return f"Hi, {self.name}"

u = User("Tom", 18)     # 注意：不用 new
print(u.greet())        # Hi, Tom
```

对照 Java：

```java
class User {
    String name; int age;
    User(String name, int age) {        // 构造方法
        this.name = name; this.age = age;
    }
    String greet() { return "Hi, " + name; }
}
User u = new User("Tom", 18);
```

关键差异：

- `__init__` 是 **构造方法**（前后双下划线的方法叫 "dunder method"，是 Python 的特殊方法）。
- `self` 就是 Java 的 `this`，但 Python **要求显式写成每个方法的第一个参数**。调用 `u.greet()` 时不用传 self，Python 自动把 `u` 传进去。
- **创建对象不写 `new`**，直接 `User(...)`。

> 在 FastAPI / Pydantic 里你很少手写 `__init__`，但读到别人的类时要认识它。

## 1.10 装饰器 Decorator ★★★

**这是 Java 开发者看 FastAPI 代码最大的拦路虎**，必须搞懂。`@app.get(...)`、`@app.post(...)` 全是装饰器。

### 它长得像 Java 注解，但本质完全不同

```python
@app.get("/users")
def list_users():
    ...
```

第一眼像 Java 的 `@GetMapping("/users")`。但：

- **Java 注解只是"贴标签"**，本身不执行任何逻辑，要靠框架用反射去读它。
- **Python 装饰器是真的把你的函数包了一层、替换成一个新函数**，定义的那一刻就执行了。

### 从零理解装饰器

装饰器本质是：**一个接收函数、返回新函数的函数**。

```python
def my_decorator(func):
    def wrapper(*args, **kwargs):
        print("调用前")
        result = func(*args, **kwargs)   # 调用原函数
        print("调用后")
        return result
    return wrapper

@my_decorator
def hello():
    print("hello")

hello()
# 输出：
# 调用前
# hello
# 调用后
```

`@my_decorator` 这一行，等价于：

```python
hello = my_decorator(hello)
```

也就是说，`hello` 已经被换成了 `wrapper`。这就是装饰器能"在不改原函数代码的前提下加逻辑"的原因——和 OkHttp Interceptor 在请求前后插逻辑是同一种思想。

### FastAPI 的装饰器其实是"装饰器工厂"

注意 `@app.get("/users")` 后面带了括号和参数。它的执行分两步：

```python
@app.get("/users")
def list_users():
    ...
```

1. 先调用 `app.get("/users")`，它**返回一个装饰器**；
2. 这个装饰器再去包裹 `list_users`，顺便把 `list_users` 注册成"GET /users 的处理函数"。

你不需要会自己写这种工厂，但要知道：**`@app.get("/users")` = "把下面这个函数登记为 GET /users 的处理器"**。这就够读懂代码了。

> Android 类比：和 Retrofit 里 `@GET("users")` 标在接口方法上几乎是同一个心智模型，只是方向相反——Retrofit 描述的是"客户端要发的请求"，FastAPI 描述的是"服务端要处理的请求"。

## 1.11 `with` 上下文管理器

```python
with open("data.txt") as f:
    content = f.read()
# 离开 with 块，文件自动关闭
```

这就是 Java 的 **try-with-resources**：

```java
try (var f = new FileReader("data.txt")) {
    ...
}   // 自动 close
```

`with` 保证资源（文件、数据库连接、锁等）用完自动释放。FastAPI 里管理数据库 session 经常用它。

## 1.12 异常处理

```python
try:
    risky()
except ValueError as e:
    print(f"值错误: {e}")
except (KeyError, IndexError):
    print("键或索引错误")
else:
    print("没出错才执行")
finally:
    print("总会执行")
```

对照 Java：`try` / `catch` / `finally` 一一对应，`except` = `catch`。两个差异：

- Python **没有"受检异常"**，函数签名不声明会抛什么异常，不强制 try。
- 多了个 `else`（没异常时执行），用得少，知道即可。

抛异常用 `raise`：

```python
raise ValueError("age must be positive")   # 相当于 throw new ...
```

## 1.13 import 与模块

- 一个 `.py` 文件就是一个 **模块**（module）。
- 一个含 `__init__.py` 的文件夹是一个 **包**（package）。

```python
import os                          # 导入整个模块，用 os.getenv(...)
from fastapi import FastAPI        # 只导入 FastAPI 这个名字
from app.models import User, Task  # 从自己项目的 app/models.py 导入
import numpy as np                 # 起别名
```

大致对应 Java 的 `import`，但 Python 可以"只导入某个类/函数"甚至"导入整个模块再用 `模块.成员` 访问"。`from X import Y` 是最常见的形式。

## 1.14 虚拟环境与 pip / uv（运行代码必备）

Java 用 Maven/Gradle 管理依赖，且依赖天然按项目隔离。Python 默认所有 `pip install` 装到**全局**，多个项目容易打架，所以有了 **虚拟环境**：给每个项目一个独立的依赖目录。

```bash
# 传统方式
python -m venv .venv          # 创建虚拟环境（生成 .venv 文件夹）
source .venv/bin/activate     # 激活（mac/linux）
pip install fastapi uvicorn   # 装依赖，只装进这个项目

# 新工具 uv（更快，路线图也推荐）
uv venv
uv pip install fastapi uvicorn
```

类比：

- `.venv` 文件夹 ≈ 项目私有的依赖目录（类似 Gradle 帮你隔离的那份依赖）。
- `requirements.txt` ≈ `build.gradle` 的 dependencies 块（列出项目依赖）。
- `pip install` ≈ Gradle 同步依赖。

看到项目里有 `.venv/`、`requirements.txt` 或 `pyproject.toml`，知道它们是干这个的就行。

---

# Part 2 · async / await ★★

FastAPI 的函数你会看到两种写法：`def` 和 `async def`。必须搞懂区别，否则读不懂也容易写出性能问题。

## 2.1 为什么需要异步

后端大量时间花在 **等 I/O**：等数据库、等第三方 API、等大模型返回。同步代码在等的时候线程被"占着干等"。异步让一个线程在等待时去处理别的请求，**用少量线程扛住大量并发 I/O**。

Java 里你可能用过 `CompletableFuture`、Reactor、或 Java 21 的虚拟线程来解决类似问题——目标一样，机制不同。Python 用的是 **单线程事件循环（event loop）** + `async/await` 关键字。

## 2.2 语法

```python
import httpx

async def fetch_user(uid: int) -> dict:
    async with httpx.AsyncClient() as client:
        resp = await client.get(f"https://api.example.com/users/{uid}")
        return resp.json()
```

- `async def` 定义一个 **协程函数**。
- `await` 表示"这里要等一个异步操作完成，等的时候把控制权交还事件循环去干别的"。
- **`await` 只能写在 `async def` 里面**。
- 调用 `async def` 函数 **不会立刻执行**，会返回一个协程对象，必须 `await` 它（或交给事件循环）才真正跑。这点和 Java 调普通方法立即执行不同，初学最容易踩。

```python
fetch_user(1)          # ❌ 只是创建了协程，并没有发请求
await fetch_user(1)    # ✅ 真正执行并拿到结果（且必须在 async 函数里）
```

## 2.3 在 FastAPI 里：什么时候用哪个

这是实践中最该记住的一条规则：

| 你的处理函数里… | 该用 | 原因 |
|---|---|---|
| 有 `await`（异步数据库/异步 HTTP/调大模型 SDK 的异步接口）| `async def` | 充分利用异步并发 |
| 全是同步阻塞调用（同步 DB 驱动、`requests` 库、CPU 计算）| 普通 `def` | FastAPI 会自动把它丢到线程池，**不会阻塞事件循环** |

```python
@app.get("/a")
async def handler_a():
    data = await fetch_user(1)   # 里面有 await，用 async def
    return data

@app.get("/b")
def handler_b():
    return {"ok": True}          # 没有 await，普通 def 即可
```

> **最关键的坑**：千万不要在 `async def` 里调用一个**同步阻塞**的函数（比如 `time.sleep()`、同步数据库查询）。那会卡住整个事件循环，把所有并发都拖垮。如果一段逻辑是阻塞的，要么放进普通 `def`，要么用异步版本的库。

看 AI 代码时，先看函数是不是 `async def`、里面有没有 `await`，就能判断它有没有正确处理异步。

---

# Part 3 · FastAPI 核心

## 3.1 最小可运行例子

```python
# main.py
from fastapi import FastAPI

app = FastAPI()          # 创建应用实例，全局就这一个

@app.get("/")
def root():
    return {"message": "hello"}   # 返回 dict，FastAPI 自动转成 JSON
```

启动：

```bash
uvicorn main:app --reload
```

- `main:app` = "main.py 文件里的 app 对象"。
- `--reload` = 改代码自动重启（开发用）。
- `uvicorn` 是真正跑你服务的 **服务器**（ASGI server），约等于把你的应用挂到一个 HTTP 服务器上。

打开 `http://127.0.0.1:8000` 看响应，打开 `http://127.0.0.1:8000/docs` 看自动生成的接口文档（见 3.9）。

## 3.2 路由：和 Retrofit 是镜像关系

```python
@app.get("/items")          # GET
@app.post("/items")         # POST
@app.put("/items/{id}")     # PUT
@app.delete("/items/{id}")  # DELETE
```

Android 里你用 Retrofit 这样**声明客户端请求**：

```java
@GET("items/{id}")
Call<Item> getItem(@Path("id") String id);
```

FastAPI 是**服务端**版本，结构几乎一样：路径里 `{id}` 是占位符，函数参数接住它。心智模型可以直接套用 Retrofit。

## 3.3 三类参数：路径 / 查询 / 请求体

这是 FastAPI 最体现"靠类型标注自动解析"的地方。**参数从哪来，由它的类型和声明方式决定**：

```python
from pydantic import BaseModel

class ItemIn(BaseModel):     # 请求体模型，见 3.4
    name: str
    price: float

@app.post("/items/{category}")
def create_item(
    category: str,           # ① 路径参数：名字和 {category} 对上 → 来自 URL 路径
    item: ItemIn,            # ② 请求体：类型是 Pydantic 模型 → 来自 JSON body
    q: str | None = None,    # ③ 查询参数：普通类型且有默认值 → 来自 ?q=xxx
):
    return {"category": category, "item": item, "q": q}
```

对应这样一个请求：

```
POST /items/books?q=hot
Content-Type: application/json

{ "name": "FastAPI 入门", "price": 59.0 }
```

FastAPI 的判断规则（读代码时按这个对号入座）：

1. 参数名出现在路径 `{}` 里 → **路径参数**（对应 Retrofit 的 `@Path`）。
2. 参数类型是 **Pydantic 模型** → **请求体**（对应 Retrofit 的 `@Body`）。
3. 其余普通类型的参数 → **查询参数**（对应 Retrofit 的 `@Query`）。

> 它会顺便自动做类型转换和校验：`price` 传成 `"abc"` 会自动返回 422 错误，不用你写校验代码。这就是前面强调"类型标注很重要"的兑现。

## 3.4 Pydantic 模型 ★★（最像 Gson/Moshi 的 data 类 + 校验）

Pydantic 的 `BaseModel` 是 FastAPI 处理 JSON 的核心。把它理解成：**一个带类型、带自动校验、能和 JSON 互转的数据类**——相当于 Gson/Moshi 的 model 类 + 一层 Hibernate Validator 校验。

```python
from pydantic import BaseModel, Field

class TaskIn(BaseModel):
    title: str
    priority: int = 1                       # 有默认值 = 可选字段
    tags: list[str] = []
    description: str | None = None          # 可空

class TaskOut(BaseModel):
    id: int
    title: str
    done: bool
```

它替你做的事：

- **解析**：自动把请求 JSON 变成 `TaskIn` 对象。
- **校验**：类型不对、缺必填字段 → 自动返回 422，不用手写。
- **序列化**：返回时自动把对象转回 JSON。

加约束用 `Field`：

```python
class TaskIn(BaseModel):
    title: str = Field(min_length=1, max_length=100)
    priority: int = Field(default=1, ge=1, le=5)   # ge=>=, le=<=
```

类似你在 Java DTO 上贴 `@NotNull` `@Size` `@Min/@Max` 校验注解，只是写法更集中。

常用方法（Pydantic v2）：

```python
task = TaskIn(title="写文档")     # 创建对象（不用 new）
task.model_dump()                # → dict
task.model_dump_json()           # → JSON 字符串
TaskIn.model_validate(some_dict) # 从 dict 反向构造并校验
```

> 看到旧代码里的 `.dict()` / `.json()` / `parse_obj()` 是 Pydantic v1 的写法，作用一样，知道即可。

## 3.5 response_model：声明返回结构

```python
@app.post("/tasks", response_model=TaskOut)
def create_task(task: TaskIn) -> TaskOut:
    ...
```

`response_model=TaskOut` 让 FastAPI 按 `TaskOut` 过滤/校验返回值——**模型里没有的字段不会泄露出去**（比如不会把密码字段返回给前端）。相当于强约束了出参 DTO 的形状。

## 3.6 状态码与 HTTPException

正常返回默认 200。想返回错误：

```python
from fastapi import HTTPException

@app.get("/tasks/{id}")
def get_task(id: int):
    task = db.get(id)
    if task is None:
        raise HTTPException(status_code=404, detail="task not found")
    return task
```

`raise HTTPException(...)` 就是"抛出一个会被 FastAPI 翻译成 HTTP 错误响应的异常"，类似在 Java 里抛一个被全局异常处理器映射成 404 的异常。`raise` = `throw`。

## 3.7 依赖注入 Depends ★★

`Depends` 是 FastAPI 的依赖注入机制。思想接近 Dagger/Hilt，但更"显式、按请求级别"。

```python
from fastapi import Depends

def get_db():                       # 一个"依赖"：提供数据库 session
    db = SessionLocal()
    try:
        yield db                    # 把 db 交出去用
    finally:
        db.close()                  # 请求结束后自动清理

@app.get("/tasks")
def list_tasks(db = Depends(get_db)):   # 声明：我需要一个 db
    return db.query(...)
```

读法：**参数 `db = Depends(get_db)` 表示"这个值由 FastAPI 调用 `get_db()` 来提供"**，你不用自己创建/关闭 db，框架在每次请求时帮你注入和清理。

和 Dagger 的差异：

- 不是靠注解 + 编译期生成代码，而是 **运行时、显式写在参数上**。
- 作用域天然是 **单次请求**：每个请求拿到自己的依赖实例，请求结束自动清理（靠上面 `yield` + `finally` 实现）。

依赖还能嵌套（依赖里再 `Depends` 别的依赖），常用于鉴权：

```python
def get_current_user(token: str = Depends(get_token)) -> User:
    ...

@app.get("/me")
def me(user: User = Depends(get_current_user)):
    return user
```

看到一长串 `Depends`，就理解成"框架按需帮我把这些东西准备好注入进来"即可。

## 3.8 中间件：约等于 OkHttp Interceptor

```python
@app.middleware("http")
async def add_timing(request, call_next):
    import time
    start = time.perf_counter()
    response = await call_next(request)     # 放行，去执行真正的处理函数
    cost = time.perf_counter() - start
    response.headers["X-Process-Time"] = str(cost)
    return response
```

这和 OkHttp 的 `Interceptor` 是同一个套路：在请求到达处理函数**之前**和响应返回**之后**插入逻辑。`call_next(request)` 就是 OkHttp 里的 `chain.proceed(request)`——放行到下一环。

典型用途：统一日志、计时、加请求 ID、CORS、鉴权。路线图里"trace / latency 统计"很多就靠中间件实现。

## 3.9 自动文档（Swagger）

FastAPI 根据你的类型标注和 Pydantic 模型，**自动生成交互式接口文档**，零额外代码：

- `http://127.0.0.1:8000/docs` —— Swagger UI，能直接在页面上发请求测试。
- `http://127.0.0.1:8000/redoc` —— 另一种风格的文档。

这也是 FastAPI 相比手写后端的一大省事点。

## 3.10 典型项目结构

AI 生成中大型项目时常见这种分层（看到能认出各层职责即可）：

```
app/
├── main.py          # 创建 app、挂载路由、配置中间件（入口）
├── routers/         # 各模块的路由（@app.get 们）  ≈ Controller 层
│   └── tasks.py
├── models.py        # SQLAlchemy ORM 模型（数据库表）  ≈ Entity
├── schemas.py       # Pydantic 模型（出入参 DTO）      ≈ DTO/VO
├── crud.py          # 数据库读写逻辑                   ≈ Repository/DAO
├── deps.py          # Depends 用的依赖（get_db 等）
└── database.py      # 数据库连接配置
requirements.txt     # 依赖清单
```

> 注意区分两类"模型"：**SQLAlchemy 模型**对应数据库表（持久层），**Pydantic 模型**对应接口出入参（DTO 层）。AI 经常两个都生成，别看混。

## 3.11 启动服务

```bash
uvicorn app.main:app --reload --port 8000
```

生产环境一般用 `uvicorn`/`gunicorn` 配多 worker，或丢进 Docker（那是路线图第 4 周的事，这里先知道开发期就这一行命令）。

---

# Part 4 · 逐行精读一段 AI 生成代码

下面是一段典型的、AI 很可能生成的 Task API 片段。逐行注释，把前面所有知识点串起来。**能独立读懂这段，本文目标就达到了。**

```python
from fastapi import FastAPI, HTTPException, Depends   # 从 fastapi 包导入这几个名字
from pydantic import BaseModel, Field                 # 导入 Pydantic 基类和字段约束

app = FastAPI(title="Task API")                        # 创建应用，title 会显示在 /docs

# ---------- 数据模型（Pydantic / DTO 层）----------

class TaskIn(BaseModel):                               # 入参 DTO，约等于带校验的数据类
    title: str = Field(min_length=1)                   # 必填字符串，长度≥1，否则自动 422
    priority: int = 1                                  # 有默认值 → 可选字段

class TaskOut(BaseModel):                              # 出参 DTO
    id: int
    title: str
    priority: int
    done: bool

# ---------- 假装这是数据库 ----------

_tasks: dict[int, TaskOut] = {}                        # 类型：int→TaskOut 的字典，当内存库
_next_id = 1                                           # 自增 id

def get_store() -> dict[int, TaskOut]:                 # 一个依赖：提供"数据库"
    return _tasks

# ---------- 路由 ----------

@app.post("/tasks", response_model=TaskOut, status_code=201)  # 注册 POST /tasks，出参按 TaskOut，成功返回 201
def create_task(task: TaskIn, store=Depends(get_store)):      # task 是请求体；store 由 Depends 注入
    global _next_id                                    # 声明要修改外层的 _next_id（Python 改全局变量需声明）
    new = TaskOut(                                     # 构造出参对象（不用 new）
        id=_next_id,
        title=task.title,                              # 用关键字参数赋值，可读性好
        priority=task.priority,
        done=False,
    )
    store[_next_id] = new                              # 存进字典
    _next_id += 1                                      # 自增（Python 没有 ++）
    return new                                         # 返回对象，FastAPI 自动转 JSON

@app.get("/tasks", response_model=list[TaskOut])      # GET /tasks，返回 TaskOut 列表
def list_tasks(store=Depends(get_store)):
    return list(store.values())                        # 字典的所有值，转成 list

@app.get("/tasks/{task_id}", response_model=TaskOut)  # 路径里有 {task_id}
def get_task(task_id: int, store=Depends(get_store)): # task_id 名字对上 {task_id} → 路径参数，自动转 int
    task = store.get(task_id)                          # dict.get：取不到返回 None，不抛异常
    if task is None:                                   # 判空用 is None
        raise HTTPException(status_code=404, detail="task not found")  # 抛出→变成 404 响应
    return task
```

如果上面每一行你都能说出"它在干嘛、为什么这么写"，那 AI 生成的大部分 FastAPI 业务代码你都能读懂了。读不顺的行，回到前面对应章节再看一眼。

---

# Part 5 · Java ↔ Python / FastAPI 速查表

### 语法对照

| 概念 | Java / Android | Python / FastAPI |
|---|---|---|
| 代码块 | `{ }` | 缩进 + `:` |
| 行尾 | `;` | 无 |
| 空值 | `null` | `None`（判空用 `is None`）|
| 构造对象 | `new User()` | `User()` |
| this | `this` | `self`（显式写成方法首参）|
| 构造方法 | `User(...)` | `__init__(self, ...)` |
| 可变参数 | `Object... args` | `*args` |
| 命名传参 | 无（靠重载/Builder）| `f(name="Tom")` |
| 字符串格式化 | `String.format(...)` | `f"{x}"` |
| 集合变换 | Stream `map/filter` | 推导式 `[... for ... if ...]` |
| 资源自动释放 | try-with-resources | `with ... as ...` |
| 异常 | `try/catch/finally`，受检异常 | `try/except/finally`，无受检 |
| 抛异常 | `throw` | `raise` |
| 注解 | `@GetMapping`（标签，靠反射）| 装饰器 `@app.get`（真包裹函数）|
| 依赖注入 | Dagger/Hilt（编译期）| `Depends`（运行时、按请求）|
| 拦截器 | OkHttp Interceptor | `@app.middleware` |
| JSON model | Gson/Moshi data 类 | Pydantic `BaseModel` |
| 校验注解 | `@NotNull/@Size/@Min` | `Field(min_length=, ge=, le=)` |
| HTTP 客户端 | Retrofit/OkHttp | httpx |
| 依赖管理 | Gradle + build.gradle | pip/uv + requirements.txt |
| 依赖隔离 | Gradle 自动按项目 | venv 虚拟环境 |

### 看 FastAPI 代码的"三步速读法"

1. **看装饰器**：`@app.get/post/...("/路径")` → 这是哪个 HTTP 方法、哪条路径的处理函数。
2. **看函数参数**：路径里出现的名字=路径参数；Pydantic 类型=请求体；其余普通类型=查询参数；`Depends(...)`=框架注入的依赖。
3. **看返回 + response_model**：返回的 dict/对象会转成 JSON；`response_model` 约束了出参形状。

### 几个最容易看错的点（记住能省很多疑惑）

- `@xxx` 不是单纯的标签，是**真的在包裹/注册**你的函数。
- `param=value` 的传参是**关键字参数**，不是赋值语句。
- `[x for x in xs if ...]` 是**推导式**，等于 Stream 的 map/filter。
- `async def` 里必须配 `await`；别在 `async def` 里写同步阻塞调用。
- 判空是 `is None`，自增是 `x += 1`（没有 `++`）。
- 两种"模型"别混：SQLAlchemy 模型=数据库表，Pydantic 模型=接口 DTO。

---

## 参考链接 / 延伸阅读

> 下面都是官方文档，最权威但也随版本更新；链接偶尔会调整，失效时按标题搜索即可。**FastAPI 官方文档右上角可切换中文**，对你很友好。

**Python 语言**
- 官方教程：https://docs.python.org/3/tutorial/
- 类型标注 typing：https://docs.python.org/3/library/typing.html
- asyncio（异步底层）：https://docs.python.org/3/library/asyncio.html

**FastAPI**
- 官方文档（含中文，强烈推荐）：https://fastapi.tiangolo.com/
- 教程（从入门到部署，边读边动手）：https://fastapi.tiangolo.com/tutorial/
- 并发与 async/await（讲得很通俗，专治“何时用 async”）：https://fastapi.tiangolo.com/async/
- 依赖注入 Depends：https://fastapi.tiangolo.com/tutorial/dependencies/

**Pydantic**
- 官方文档：https://docs.pydantic.dev/

**虚拟环境 / 依赖管理**
- uv：https://docs.astral.sh/uv/

---

## 下一步建议

读完这份文档后，最有效的巩固方式是 **对照路线图第 1 周的 Task API 项目，让 AI 生成一份完整代码，然后用本文 Part 4 的方式逐行读一遍**——遇到读不懂的语法，回到对应章节查。能读懂、能让 AI 按你的意思改，就达到了"看懂 AI 代码"的目标。
