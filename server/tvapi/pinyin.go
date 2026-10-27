package main

import (
	"log"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/mozillazg/go-pinyin"
)

// TMDB 的搜索不认拼音首字母：搜「kq」找不到《空枪》，搜「kongqiang」也
// 不一定行。电视遥控器又打不出汉字，所以这里自己攒一份"标题 → 拼音"的
// 内存索引：每次代理 TMDB 的榜单/搜索都往里塞，启动后再预热一批中文分类。
// 用户输入纯字母时先在这个索引里匹配，命中的排在 TMDB 结果前面。

var pinyinArgs = func() pinyin.Args {
	a := pinyin.NewArgs()
	a.Style = pinyin.Normal // 不要声调，只要字母
	a.Heteronym = false
	return a
}()

// pinyinForms 把标题转成 (全拼, 首字母)。
// 英文/数字原样保留（转小写），标点和空格丢掉：
// "空枪" → ("kongqiang", "kq")；"Spider-Man" → ("spiderman", "spiderman")。
func pinyinForms(s string) (full, initials string) {
	var f, i strings.Builder
	for _, r := range s {
		switch {
		case r < unicode.MaxASCII:
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
				lr := unicode.ToLower(r)
				f.WriteRune(lr)
				i.WriteRune(lr)
			}
		case unicode.Is(unicode.Han, r):
			res := pinyin.Pinyin(string(r), pinyinArgs)
			if len(res) > 0 && len(res[0]) > 0 && res[0][0] != "" {
				syll := res[0][0]
				f.WriteString(syll)
				i.WriteByte(syll[0])
			}
		}
	}
	return f.String(), i.String()
}

type indexEntry struct {
	item searchItem
	// 全拼 / 首字母 / 原名对应的两种形式
	forms []string
	// 标题里有没有汉字。有的话说明拼音是"翻译"出来的，首字母命中时优先级更高：
	// 敲 kq 的人要的是《空枪》，不是片名就叫 KQ 的那部
	han       bool
	updatedAt int64
}

type searchIndex struct {
	mu      sync.RWMutex
	entries map[string]indexEntry // key: type-tmdbId
}

func newSearchIndex() *searchIndex {
	return &searchIndex{entries: make(map[string]indexEntry, 4096)}
}

func (x *searchIndex) add(items []searchItem) {
	if len(items) == 0 {
		return
	}
	now := time.Now().Unix()
	x.mu.Lock()
	defer x.mu.Unlock()
	for _, it := range items {
		if it.Title == "" || it.TmdbID == 0 {
			continue
		}
		key := it.Type + "-" + strconv.Itoa(it.TmdbID)
		full, initials := pinyinForms(it.Title)
		forms := []string{full, initials}
		if alt := it.OriginalTitle; alt != "" && alt != it.Title {
			af, ai := pinyinForms(alt)
			forms = append(forms, af, ai)
		}
		x.entries[key] = indexEntry{
			item:      it,
			forms:     forms,
			han:       strings.ContainsFunc(it.Title, func(r rune) bool { return unicode.Is(unicode.Han, r) }),
			updatedAt: now,
		}
	}
}

func (x *searchIndex) size() int {
	x.mu.RLock()
	defer x.mu.RUnlock()
	return len(x.entries)
}

// search 在索引里按"全拼/首字母前缀"找条目。
// 完全相等的排最前，其次是前缀命中，长查询（≥3）才做包含匹配。
func (x *searchIndex) search(q string, limit int) []searchItem {
	q = strings.ToLower(strings.TrimSpace(q))
	if q == "" || !isASCIIWord(q) {
		return nil
	}
	type hit struct {
		item  searchItem
		score int
	}
	x.mu.RLock()
	hits := make([]hit, 0, 32)
	for _, e := range x.entries {
		best := 0
		for idx, f := range e.forms {
			// forms 是 [全拼, 首字母, 原名全拼, 原名首字母]。首字母精确命中
			// 权重最高：用户敲 kq 想的就是《空枪》，不是名字真叫 KQ 的片子。
			isInitials := idx%2 == 1
			switch {
			case f == q && isInitials:
				best = maxInt(best, 120)
			case f == q:
				best = maxInt(best, 100)
			case strings.HasPrefix(f, q) && isInitials:
				best = maxInt(best, 70)
			case strings.HasPrefix(f, q):
				best = maxInt(best, 55)
			case len(q) >= 3 && strings.Contains(f, q):
				best = maxInt(best, 30)
			}
		}
		if best > 0 {
			if e.han {
				best += 5
			}
			hits = append(hits, hit{e.item, best})
		}
	}
	x.mu.RUnlock()

	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].score != hits[j].score {
			return hits[i].score > hits[j].score
		}
		if hits[i].item.Rating != hits[j].item.Rating {
			return hits[i].item.Rating > hits[j].item.Rating
		}
		return hits[i].item.TmdbID > hits[j].item.TmdbID
	})
	out := make([]searchItem, 0, limit)
	for _, h := range hits {
		if len(out) >= limit {
			break
		}
		out = append(out, h.item)
	}
	return out
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// isASCIIWord 判断查询是不是"纯字母数字"——只有这种才可能是拼音/首字母。
func isASCIIWord(s string) bool {
	if len(s) == 0 || len(s) > 16 {
		return false
	}
	hasLetter := false
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z':
			hasLetter = true
		case r >= '0' && r <= '9':
		default:
			return false
		}
	}
	return hasLetter
}

// warmIndex 后台把中文相关榜单的前几页拉进索引。
//
// 只跑一次，失败也无所谓：用户自己浏览过的榜单/搜索结果同样会进索引。
// 4 并发是因为 TMDB 走代理单次 1~4s，串行 32 个请求要一分多钟。
func (s *server) warmIndex() {
	feeds := []string{"cn_movie", "cn_tv", "hot_movie", "new_movie", "hot_tv", "new_tv", "us_tv", "anime"}
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for _, name := range feeds {
		spec, ok := feedFor(name)
		if !ok {
			continue
		}
		for page := 1; page <= 6; page++ {
			wg.Add(1)
			sem <- struct{}{}
			go func(spec feedSpec, page int) {
				defer wg.Done()
				defer func() { <-sem }()
				resp, err := s.fetchFeedPage(spec, "zh-CN", page)
				if err != nil {
					return
				}
				s.index.add(resp.Items)
			}(spec, page)
		}
	}
	wg.Wait()
	log.Printf("pinyin index warmed: %d entries", s.index.size())
}
