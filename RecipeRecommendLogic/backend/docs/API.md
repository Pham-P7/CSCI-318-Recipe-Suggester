# 前端接口交付说明

默认后端：`http://localhost:8080`。数据库只由后端访问，前端不接收 Cloudflare Token。
前端来源尚未确定；本机开发暂预设 `http://localhost:5173` 和 `http://127.0.0.1:5173`。

## 列表与参数

`GET /api/recipes`

| 参数 | 含义 |
|---|---|
| limit | 默认 50，允许 1–100 |
| offset | 默认 0，必须非负；对筛选后的结果分页 |
| keyword | 菜谱名称字面包含；`%`、`_` 不作为通配符 |
| category | 分类完整名称匹配 |
| minCalories / maxCalories | 可单边使用；非负有限数字，闭区间，最小值不能大于最大值 |
| ingredients | 可重复；菜谱必须包含全部指定完整名称，允许额外食材 |
| excludedIngredients | 可重复；含任一指定完整名称就排除；未知/无效食材不能被当作确认无禁用项 |

所有启用条件以 AND 组合。名称匹配会去首尾空白、忽略大小写；不自动把 carrot 和 carrots 合并，也不会把 egg 当作 eggplant。
热量使用数据库原值，不除以 servings。结果按 recipeId 升序，分页以固定数据为前提；不返回总条数。

```text
/api/recipes?ingredients=chicken&ingredients=onion&excludedIngredients=milk&limit=50&offset=0
```

列表结构示例：

```json
{
  "items": [{
    "recipeId": 1,
    "name": "Low-Fat Berry Blue Frozen Dessert",
    "category": "Frozen Desserts",
    "calories": 170.9,
    "servings": 4,
    "imageUrl": "https://example.test/cover.jpg"
  }],
  "limit": 1,
  "offset": 0,
  "hasMore": true
}
```

下一页使用 `offset + limit`。更改筛选时把 offset 重置为 0。`hasMore=false` 时停止翻页。
有效但没有命中的搜索返回 200、`items: []`、`hasMore: false`。

## 详情与缺失值

`GET /api/recipes/{recipeId}` 返回 recipeId、name、description、category、calories、servings、imageUrl、instructions、ingredients。
instructions 是字符串数组或 null，数组项也可能为 null。ingredients 是数组，每项包含 position、ingredientName、quantity、pairStatus。
当前现有库没有已核验的食材用量配对：quantity 为 null，pairStatus 为 unresolved。
未知 calories/servings/imageUrl 保留 null。前端展示“未知”或占位图，不把 null 变成 0，不把缺失步骤伪装成已知步骤。

## Vite / React 调用示例

前端 `.env.local` 只设置后端地址：

```dotenv
VITE_RECIPE_API_BASE_URL=http://localhost:8080
```

不要在 VITE_* 变量中设置数据库 Token，这些变量会进入浏览器构建产物。
下面代码可放入前端 API 模块；不依赖额外库：

```js
const base = import.meta.env.VITE_RECIPE_API_BASE_URL || 'http://localhost:8080';

async function get(path, params) {
  const url = new URL(path, base);
  if (params) url.search = params.toString();
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
  return body;
}

export function listRecipes({ ingredients = [], excludedIngredients = [], ...filters } = {}) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  for (const name of ingredients) params.append('ingredients', name);
  for (const name of excludedIngredients) params.append('excludedIngredients', name);
  return get('/api/recipes', params);
}

export function getRecipe(recipeId) {
  return get(`/api/recipes/${encodeURIComponent(recipeId)}`);
}
```

重复参数用 append；不要把多个食材用逗号拼成一个参数，食材名称本身可以含逗号。
页面需要分别处理加载、空结果、成功和错误状态，并避免旧请求覆盖用户最新一次筛选。

## 跨域和错误

后端通过 `RECIPE_CORS_ALLOWED_ORIGINS` 接收逗号分隔的完整来源，例如：

```sh
export RECIPE_CORS_ALLOWED_ORIGINS='https://frontend.example.com'
```

来源仅包含协议、域名和端口，不带路径或末尾 `/`；不接受 `*`。设置为空字符串可关闭跨域授权。
允许 GET/HEAD 和浏览器相应预检，不允许跨域写入方法，也不启用跨域 Cookie。
CORS 是浏览器访问规则，不是账号鉴权或数据库写权限。

| 状态 | 含义 |
|---|---|
| 400 | 参数格式、范围、分页或绑定参数预算不合法 |
| 404 | 菜谱不存在 |
| 500 | 数据损坏或其他意外错误；返回通用消息 |
| 502 / 504 | 云端请求失败 / 超时；不能当作没有菜谱 |

业务错误使用 `{"message":"..."}`。浏览器网络或 CORS 错误可能直接让 fetch 抛出异常，需由页面 catch 处理。
`GET /api/health` 只证明 Spring 存活，不代表 D1 可用。
