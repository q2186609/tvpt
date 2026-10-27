package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

// streamItem 是返回给客户端的片源条目：结构化的，不用再解析 emoji 文案。
type streamItem struct {
	Name       string `json:"name"`
	Title      string `json:"title"`
	URL        string `json:"url"`
	Seeders    int    `json:"seeders"`
	SizeBytes  int64  `json:"sizeBytes"`
	Resolution string `json:"resolution,omitempty"`
	Source     string `json:"source,omitempty"`
	SeasonPack bool   `json:"seasonPack,omitempty"`
}

var (
	reSeeders = regexp.MustCompile(`👤\s*(\d+)`)
	reSize    = regexp.MustCompile(`💾\s*([\d.]+)\s*(GiB|MiB|GB|MB)`)
	reEp      = regexp.MustCompile(`(?i)S(\d{1,2})E(\d{1,4})`)
	reSeason  = regexp.MustCompile(`(?i)S(\d{1,2})(?:[^E0-9]|$)`)
	// 国内站点常用「第12集」而不是 S01E12
	reEpCN   = regexp.MustCompile(`第\s*(\d{1,4})\s*[集话話]`)
	reRes    = regexp.MustCompile(`(?i)(2160p|1080p|720p|480p)`)
	reSource = regexp.MustCompile(`(?i)(UHD Blu-?ray|Blu-?ray|WEB-?DL|WEBRip|Remux|HDTV|HDTS)`)
)

func (s *server) handleStreams(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	typ := q.Get("type") // movie | series
	imdb := strings.TrimSpace(q.Get("imdbId"))
	if imdb == "" || (typ != "movie" && typ != "series") {
		writeErr(w, http.StatusBadRequest, "need type=movie|series and imdbId")
		return
	}
	season := atoiOr(q.Get("season"), 0)
	episode := atoiOr(q.Get("episode"), 0)

	id := imdb
	if typ == "series" {
		if season <= 0 || episode <= 0 {
			writeErr(w, http.StatusBadRequest, "series needs season and episode")
			return
		}
		id = fmt.Sprintf("%s:%d:%d", imdb, season, episode)
	}

	key := fmt.Sprintf("streams:%s:%s", typ, id)
	if body, ok := s.cache.get(key); ok {
		var cached streamsResponse
		if err := json.Unmarshal(body, &cached); err == nil {
			w.Header().Set("X-Cache", "hit")
			writeStreams(w, r, cached)
			return
		}
	}

	up := fmt.Sprintf("%s/stremio/stream/%s/%s.json", strings.TrimRight(s.cfg.seedstrem, "/"), typ, url.PathEscape(id))
	client := s.http
	resp, err := client.Get(up)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "seedstrem: "+err.Error())
		return
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		writeErr(w, http.StatusBadGateway, "read seedstrem: "+err.Error())
		return
	}
	if resp.StatusCode != http.StatusOK {
		writeErr(w, http.StatusBadGateway, fmt.Sprintf("seedstrem HTTP %d: %s", resp.StatusCode, truncate(string(raw), 160)))
		return
	}

	var parsed struct {
		Streams []struct {
			Name  string `json:"name"`
			Title string `json:"title"`
			URL   string `json:"url"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		writeErr(w, http.StatusBadGateway, "seedstrem decode: "+err.Error())
		return
	}

	all := make([]streamItem, 0, len(parsed.Streams))
	for _, st := range parsed.Streams {
		if st.URL == "" {
			continue
		}
		first := firstLine(st.Title)
		item := streamItem{Name: st.Name, Title: st.Title, URL: st.URL}
		item.Seeders, item.SizeBytes = parseStats(st.Title)
		if m := reRes.FindStringSubmatch(first); m != nil {
			item.Resolution = strings.ToLower(m[1])
		}
		if m := reSource.FindStringSubmatch(first); m != nil {
			item.Source = m[1]
		}
		if typ == "series" {
			if m := reEp.FindStringSubmatch(first); m != nil {
				sn, _ := strconv.Atoi(m[1])
				ep, _ := strconv.Atoi(m[2])
				if sn != season || ep != episode {
					continue // 站点不认集号，这里必须自己筛
				}
			} else if m := reEpCN.FindStringSubmatch(first); m != nil {
				ep, _ := strconv.Atoi(m[1])
				if ep != episode {
					continue
				}
			} else if m := reSeason.FindStringSubmatch(first); m != nil {
				sn, _ := strconv.Atoi(m[1])
				if sn != season {
					continue
				}
				item.SeasonPack = true
			}
		}
		all = append(all, item)
	}

	filtered := true
	if len(all) == 0 && len(parsed.Streams) > 0 {
		// 一个都没匹配上（站点命名不规范），退回未过滤的，至少能播。
		// 剧集要更谨慎：只收完全没有集号标记的条目，否则会把 E137 当
		// E01 放出来（服务端随后 404 no matching file）。
		filtered = false
		for _, st := range parsed.Streams {
			if st.URL == "" {
				continue
			}
			first := firstLine(st.Title)
			if typ == "series" && (reEp.MatchString(first) || reEpCN.MatchString(first)) {
				continue
			}
			it := streamItem{Name: st.Name, Title: st.Title, URL: st.URL}
			it.Seeders, it.SizeBytes = parseStats(st.Title)
			all = append(all, it)
		}
	}

	// 站点常把「0 做种 + 体积未知」的僵尸条目也收录进来，点进去必然卡住；
	// 只要还有能下的条目，就不让它露脸。
	if hasLiveSeeders(all) {
		live := all[:0]
		for _, it := range all {
			if it.Seeders > 0 {
				live = append(live, it)
			}
		}
		all = live
	}

	// 同一个种子会被站点重复收录（不同 dl 链接、或者已经解析出磁力），
	// 按「标题+体积」去重，优先保留带 magnet 的那条（播放时少一次解析往返）
	all = dedupeStreams(all)
	// 做种多的排前面，整季包放最后
	sortStreams(all)

	out := streamsResponse{Streams: all, Filtered: filtered}
	if canonical, err := json.Marshal(out); err == nil {
		s.cache.put(key, canonical)
	}
	writeStreams(w, r, out)
}

// streamsResponse 是 /api/tv/streams 的响应体。
type streamsResponse struct {
	Streams  []streamItem `json:"streams"`
	Filtered bool         `json:"filtered"`
}

// writeStreams 按「和本次请求同一个源」改写片源地址后落盘。
func writeStreams(w http.ResponseWriter, r *http.Request, resp streamsResponse) {
	for i := range resp.Streams {
		resp.Streams[i].URL = rewriteStreamURL(r, resp.Streams[i].URL)
	}
	body, err := json.Marshal(resp)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "encode streams: "+err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

// rewriteStreamURL 把 seedstrem 给的绝对地址换成客户端刚用过的这个源。
//
// seedstrem 只会用它自己的 external_url（公网域名）拼播放地址。客户端如果把
// 服务器地址改成了局域网 IP，或者域名在某台机器上解析不到，片源列表能拿到、
// 点播却连不上——链接明明就来自刚刚成功的那台服务器。这里统一改成 r.Host。
func rewriteStreamURL(r *http.Request, raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || u.Path == "" {
		return raw
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = strings.TrimSpace(strings.Split(p, ",")[0])
	}
	u.Scheme = scheme
	u.Host = r.Host
	return u.String()
}

// hasLiveSeeders 报告列表里是否存在有做种的条目。
func hasLiveSeeders(items []streamItem) bool {
	for _, it := range items {
		if it.Seeders > 0 {
			return true
		}
	}
	return false
}

func dedupeStreams(items []streamItem) []streamItem {
	index := make(map[string]int, len(items))
	out := make([]streamItem, 0, len(items))
	for _, it := range items {
		key := strings.ToLower(firstLine(it.Title)) + "|" + strconv.FormatInt(it.SizeBytes, 10)
		if at, ok := index[key]; ok {
			// 已有条目更「省事」就保留原来的，否则替换
			if !strings.Contains(out[at].URL, "magnet=") && strings.Contains(it.URL, "magnet=") {
				out[at] = it
			}
			continue
		}
		index[key] = len(out)
		out = append(out, it)
	}
	return out
}

func sortStreams(items []streamItem) {
	// 简单插入排序即可，列表最多几十条
	for i := 1; i < len(items); i++ {
		for j := i; j > 0; j-- {
			a, b := items[j-1], items[j]
			swap := false
			if a.SeasonPack != b.SeasonPack {
				swap = a.SeasonPack // 整季包下沉
			} else if a.Seeders < b.Seeders {
				swap = true
			}
			if !swap {
				break
			}
			items[j-1], items[j] = items[j], items[j-1]
		}
	}
}

func firstLine(s string) string {
	if i := strings.IndexAny(s, "\r\n"); i >= 0 {
		return s[:i]
	}
	return s
}

// parseStats 从 seedstrem 的标题里抽出做种数与体积。
func parseStats(title string) (seeders int, size int64) {
	if m := reSeeders.FindStringSubmatch(title); m != nil {
		seeders, _ = strconv.Atoi(m[1])
	}
	if m := reSize.FindStringSubmatch(title); m != nil {
		v, err := strconv.ParseFloat(m[1], 64)
		if err == nil {
			switch strings.ToUpper(m[2]) {
			case "GIB", "GB":
				size = int64(v * (1 << 30))
			case "MIB", "MB":
				size = int64(v * (1 << 20))
			}
		}
	}
	return seeders, size
}
