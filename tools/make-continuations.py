"""Build the offline suggestion table from Rime Ice cn_dicts/base.dict.yaml."""
import collections
import sys
from pathlib import Path

# Frequent conversational transitions, including complete short requests.
everyday = """
你好	呀	，请问	，很高兴认识你
谢谢	你	大家	你的帮助
好的	，我知道了	，没问题	，谢谢
收到	了	，谢谢	，我来处理
帮我	看看	检查一下	确认一下	发一下
帮我检查一下	这个	代码	有没有问题
我想	问一下	了解一下	试一下
请问	一下	可以	怎么
能不能	帮我	看一下	发给我
可以	吗	帮我	试试
麻烦	你了	帮我	看一下
今天	下午	晚上	有空吗
明天	上午	下午	见
明天下午	几点	有空吗	见
早上	好	见	几点
晚上	好	见	有空吗
现在	可以	开始	方便吗
什么时候	方便	开始	有空
我	想	觉得	知道了
你	好	觉得呢	有空吗
我们	一起	可以	明天
这个	问题	脚本	怎么用
问题	解决了	是什么	在哪
检查	一下	代码	结果
看	一下	看	到了
看一下	这个	文件	有没有问题
代码	里	怎么写	有问题
运行	一下	脚本	测试
脚本	怎么运行	有问题	在哪里
Python	脚本	代码	环境
Codex	里	的设置	怎么用
输入法	设置	切换	怎么用
键盘	输入	设置	布局
一会儿	见	再说	就到
会议	几点开始	结束了	记录
没关系	，慢慢来	，下次再说
不好意思	，打扰了	，刚看到
辛苦	了	你了	大家了
没问题	，我来处理	，谢谢
到家	了	了吗	再说
""".strip()

words = set()
scores = collections.defaultdict(dict)
for line in Path(sys.argv[1]).read_text().splitlines():
    fields = line.split('\t')
    if len(fields) != 3 or not fields[2].isdigit():
        continue
    word, _, weight = fields
    weight = int(weight)
    if weight >= 1500:
        words.add(word)
    if weight < 8000 or not 3 <= len(word) <= 8 or not all('\u4e00' <= c <= '\u9fff' for c in word):
        continue
    for n in range(2, len(word)):
        prefix, suffix = word[:n], word[n:]
        scores[prefix][suffix] = max(scores[prefix].get(suffix, 0), weight)
rows = {p: sorted(tails, key=lambda t: (-tails[t], t))[:5] for p, tails in scores.items() if p in words}
for line in everyday.splitlines():
    prefix, *preferred = line.split('\t')
    rows[prefix] = list(dict.fromkeys(preferred + rows.get(prefix, [])))[:6]
out = Path(sys.argv[2])
out.write_text(''.join('\t'.join([p,*rows[p]])+'\n' for p in sorted(rows)))
