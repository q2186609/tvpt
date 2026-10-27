package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const tmdbBase = "https://api.themoviedb.org/3"

// errTMDBNotFound 把 TMDB 的 404 单独标出来：调用方要据此回 404 而不是
// 502（"这个 id 不存在"和"上游挂了"对客户端是两件事）。
var errTMDBNotFound = errors.New("tmdb: not found")

type tmdbClient struct {
	key  string
	http *http.Client
}

func newTMDB(key string) *tmdbClient {
	return &tmdbClient{key: key, http: newHTTPClient(45 * time.Second)}
}

// get 请求 TMDB 并把响应存成原始 JSON（便于直接缓存与内嵌）。
func (t *tmdbClient) get(path string, params url.Values) (json.RawMessage, error) {
	if params == nil {
		params = url.Values{}
	}
	params.Set("api_key", t.key)
	u := tmdbBase + path + "?" + params.Encode()

	// 代理链路抖动明显（同一请求 1s 和 30s 都可能），单次失败立刻重试一次
	// 比把超时拉长更快出结果。
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		resp, err := t.http.Get(u)
		if err != nil {
			lastErr = err
			continue
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode == http.StatusNotFound {
			return nil, fmt.Errorf("%w: %s", errTMDBNotFound, truncate(string(body), 120))
		}
		if resp.StatusCode != http.StatusOK {
			lastErr = fmt.Errorf("tmdb %s: HTTP %d %s", path, resp.StatusCode, truncate(string(body), 160))
			if resp.StatusCode >= 500 {
				continue
			}
			return nil, lastErr
		}
		return body, nil
	}
	return nil, lastErr
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// ---- DTO ----

type searchItem struct {
	Type          string  `json:"type"`
	TmdbID        int     `json:"tmdbId"`
	Title         string  `json:"title"`
	OriginalTitle string  `json:"originalTitle,omitempty"`
	Year          int     `json:"year,omitempty"`
	Poster        string  `json:"poster,omitempty"`
	Backdrop      string  `json:"backdrop,omitempty"`
	Rating        float64 `json:"rating,omitempty"`
	Overview      string  `json:"overview,omitempty"`
}

type searchResponse struct {
	Page       int          `json:"page"`
	HasMore    bool         `json:"hasMore"`
	TotalPages int          `json:"totalPages"`
	Items      []searchItem `json:"items"`
}

type seasonBrief struct {
	Season   int `json:"season"`
	Episodes int `json:"episodes"`
}

type detailResponse struct {
	Type          string        `json:"type"`
	TmdbID        int           `json:"tmdbId"`
	ImdbID        string        `json:"imdbId,omitempty"`
	Title         string        `json:"title"`
	OriginalTitle string        `json:"originalTitle,omitempty"`
	Year          int           `json:"year,omitempty"`
	Runtime       int           `json:"runtime,omitempty"`
	Rating        float64       `json:"rating,omitempty"`
	Genres        []string      `json:"genres,omitempty"`
	Overview      string        `json:"overview,omitempty"`
	Poster        string        `json:"poster,omitempty"`
	Backdrop      string        `json:"backdrop,omitempty"`
	Seasons       []seasonBrief `json:"seasons,omitempty"`
}

type episodeItem struct {
	Episode  int     `json:"episode"`
	Name     string  `json:"name"`
	AirDate  string  `json:"airDate,omitempty"`
	Still    string  `json:"still,omitempty"`
	Overview string  `json:"overview,omitempty"`
	Rating   float64 `json:"rating,omitempty"`
	Runtime  int     `json:"runtime,omitempty"`
}

// ---- handlers ----

// handleTrending 给首页提供热门行：/api/tv/trending?type=movie|tv
func (s *server) handleTrending(w http.ResponseWriter, r *http.Request) {
	typ := r.URL.Query().Get("type")
	if typ != "movie" && typ != "tv" {
		writeErr(w, http.StatusBadRequest, "need type=movie|tv")
		return
	}
	lang := envLang(r)
	window := r.URL.Query().Get("window")
	if window != "day" {
		window = "week"
	}
	key := "trending:" + lang + ":" + typ + ":" + window
	if body, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Cache", "hit")
		_, _ = w.Write(body)
		return
	}

	raw, err := s.tmdb.get("/trending/"+typ+"/"+window, url.Values{"language": {lang}})
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	var parsed struct {
		Page       int `json:"page"`
		TotalPages int `json:"total_pages"`
		Results    []struct {
			ID            int     `json:"id"`
			Name          string  `json:"name"`
			Title         string  `json:"title"`
			OriginalName  string  `json:"original_name"`
			OriginalTitle string  `json:"original_title"`
			PosterPath    string  `json:"poster_path"`
			BackdropPath  string  `json:"backdrop_path"`
			Overview      string  `json:"overview"`
			VoteAverage   float64 `json:"vote_average"`
			FirstAirDate  string  `json:"first_air_date"`
			ReleaseDate   string  `json:"release_date"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		writeErr(w, http.StatusBadGateway, "tmdb decode: "+err.Error())
		return
	}
	out := searchResponse{Page: parsed.Page, TotalPages: parsed.TotalPages, HasMore: parsed.Page < parsed.TotalPages}
	for _, r0 := range parsed.Results {
		title, orig, date := r0.Title, r0.OriginalTitle, r0.ReleaseDate
		if typ == "tv" {
			title, orig, date = r0.Name, r0.OriginalName, r0.FirstAirDate
		}
		out.Items = append(out.Items, searchItem{
			Type:          typ,
			TmdbID:        r0.ID,
			Title:         title,
			OriginalTitle: orig,
			Year:          yearOf(date),
			Poster:        imgURL(r0.PosterPath, "w500"),
			Backdrop:      imgURL(r0.BackdropPath, "w1280"),
			Rating:        r0.VoteAverage,
			Overview:      r0.Overview,
		})
	}
	s.index.add(out.Items)
	body, _ := json.Marshal(out)
	s.cache.put(key, body)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

func (s *server) handleSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeErr(w, http.StatusBadRequest, "missing q")
		return
	}
	lang := envLang(r)
	page := atoiOr(r.URL.Query().Get("page"), 1)
	key := "search:" + lang + ":" + strconv.Itoa(page) + ":" + q
	if body, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Cache", "hit")
		_, _ = w.Write(body)
		return
	}

	raw, err := s.tmdb.get("/search/multi", url.Values{
		"query":         {q},
		"language":      {lang},
		"page":          {strconv.Itoa(page)},
		"include_adult": {"false"},
	})
	if err != nil {
		// TMDB 挂了不代表搜不了：本地拼音索引里有的先给出去
		if hits := s.index.search(q, 24); len(hits) > 0 {
			body, _ := json.Marshal(searchResponse{Page: 1, Items: hits})
			s.cache.putTTL(key, body, time.Minute)
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			_, _ = w.Write(body)
			return
		}
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}

	var parsed struct {
		Page       int `json:"page"`
		TotalPages int `json:"total_pages"`
		Results    []struct {
			MediaType     string  `json:"media_type"`
			ID            int     `json:"id"`
			Name          string  `json:"name"`
			Title         string  `json:"title"`
			OriginalName  string  `json:"original_name"`
			OriginalTitle string  `json:"original_title"`
			PosterPath    string  `json:"poster_path"`
			BackdropPath  string  `json:"backdrop_path"`
			Overview      string  `json:"overview"`
			VoteAverage   float64 `json:"vote_average"`
			FirstAirDate  string  `json:"first_air_date"`
			ReleaseDate   string  `json:"release_date"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		writeErr(w, http.StatusBadGateway, "tmdb decode: "+err.Error())
		return
	}

	out := searchResponse{Page: parsed.Page, TotalPages: parsed.TotalPages, HasMore: parsed.Page < parsed.TotalPages}
	for _, r0 := range parsed.Results {
		if r0.MediaType != "movie" && r0.MediaType != "tv" {
			continue // person 等类型丢掉
		}
		title := r0.Title
		orig := r0.OriginalTitle
		date := r0.ReleaseDate
		if r0.MediaType == "tv" {
			title = r0.Name
			orig = r0.OriginalName
			date = r0.FirstAirDate
		}
		out.Items = append(out.Items, searchItem{
			Type:          r0.MediaType,
			TmdbID:        r0.ID,
			Title:         title,
			OriginalTitle: orig,
			Year:          yearOf(date),
			Poster:        imgURL(r0.PosterPath, "w500"),
			Backdrop:      imgURL(r0.BackdropPath, "w1280"),
			Rating:        r0.VoteAverage,
			Overview:      r0.Overview,
		})
	}
	// TMDB 自己的结果先进索引（用户搜过的片子，之后用拼音首字母也能命中）
	s.index.add(out.Items)

	// 纯字母数字的查询再跑一遍拼音索引：TMDB 不认首字母（kq ≠ 空枪），
	// 命中的排在 TMDB 结果前面——首字母精确命中的才可能是用户想要的
	if isASCIIWord(q) {
		hits := s.index.search(q, 24)
		if len(hits) > 0 {
			seen := make(map[string]bool, len(out.Items))
			merged := make([]searchItem, 0, len(hits)+len(out.Items))
			for _, it := range hits {
				seen[it.Type+"-"+strconv.Itoa(it.TmdbID)] = true
				merged = append(merged, it)
			}
			for _, it := range out.Items {
				if seen[it.Type+"-"+strconv.Itoa(it.TmdbID)] {
					continue
				}
				merged = append(merged, it)
			}
			out.Items = merged
		}
	}

	if out.Items == nil {
		out.Items = []searchItem{}
	}
	body, _ := json.Marshal(out)
	s.cache.put(key, body)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

func (s *server) handleDetail(w http.ResponseWriter, r *http.Request) {
	typ := r.URL.Query().Get("type")
	id := r.URL.Query().Get("id")
	if (typ != "movie" && typ != "tv") || id == "" {
		writeErr(w, http.StatusBadRequest, "need type=movie|tv and id")
		return
	}
	lang := envLang(r)
	key := "detail:" + lang + ":" + typ + ":" + id
	if body, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Cache", "hit")
		_, _ = w.Write(body)
		return
	}

	path := "/movie/" + id
	if typ == "tv" {
		path = "/tv/" + id
	}
	raw, err := s.tmdb.get(path, url.Values{
		"language":           {lang},
		"append_to_response": {"external_ids"},
	})
	if err != nil {
		if errors.Is(err, errTMDBNotFound) {
			writeErr(w, http.StatusNotFound, "TMDB 上没有这个条目")
			return
		}
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}

	var m struct {
		ID            int     `json:"id"`
		Title         string  `json:"title"`
		Name          string  `json:"name"`
		OriginalTitle string  `json:"original_title"`
		OriginalName  string  `json:"original_name"`
		Overview      string  `json:"overview"`
		PosterPath    string  `json:"poster_path"`
		BackdropPath  string  `json:"backdrop_path"`
		VoteAverage   float64 `json:"vote_average"`
		Runtime       int     `json:"runtime"`
		ReleaseDate   string  `json:"release_date"`
		FirstAirDate  string  `json:"first_air_date"`
		ExternalIDs   struct {
			ImdbID string `json:"imdb_id"`
		} `json:"external_ids"`
		Genres []struct {
			Name string `json:"name"`
		} `json:"genres"`
		Seasons []struct {
			SeasonNumber int `json:"season_number"`
			EpisodeCount int `json:"episode_count"`
		} `json:"seasons"`
		EpisodeRunTime []int `json:"episode_run_time"`
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		writeErr(w, http.StatusBadGateway, "tmdb decode: "+err.Error())
		return
	}

	d := detailResponse{
		Type:          typ,
		TmdbID:        m.ID,
		ImdbID:        m.ExternalIDs.ImdbID,
		Title:         firstNonEmpty(m.Title, m.Name),
		OriginalTitle: firstNonEmpty(m.OriginalTitle, m.OriginalName),
		Overview:      m.Overview,
		Poster:        imgURL(m.PosterPath, "w500"),
		Backdrop:      imgURL(m.BackdropPath, "w1280"),
		Rating:        m.VoteAverage,
		Runtime:       m.Runtime,
	}
	d.Year = yearOf(firstNonEmpty(m.ReleaseDate, m.FirstAirDate))
	for _, g := range m.Genres {
		d.Genres = append(d.Genres, g.Name)
	}
	if typ == "tv" {
		if d.Runtime == 0 && len(m.EpisodeRunTime) > 0 {
			d.Runtime = m.EpisodeRunTime[0]
		}
		for _, sn := range m.Seasons {
			if sn.SeasonNumber <= 0 {
				continue // 忽略"特别篇"这类 0 季，PT 站一般不发
			}
			d.Seasons = append(d.Seasons, seasonBrief{Season: sn.SeasonNumber, Episodes: sn.EpisodeCount})
		}
	}

	body, _ := json.Marshal(d)
	s.cache.putTTL(key, body, 6*time.Hour)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

func (s *server) handleSeason(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("tvId")
	season := atoiOr(r.URL.Query().Get("season"), 1)
	if id == "" {
		writeErr(w, http.StatusBadRequest, "need tvId")
		return
	}
	lang := envLang(r)
	key := fmt.Sprintf("season:%s:%s:%d", lang, id, season)
	if body, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Cache", "hit")
		_, _ = w.Write(body)
		return
	}

	raw, err := s.tmdb.get(fmt.Sprintf("/tv/%s/season/%d", id, season), url.Values{"language": {lang}})
	if err != nil {
		if errors.Is(err, errTMDBNotFound) {
			writeErr(w, http.StatusNotFound, "TMDB 上没有这一季")
			return
		}
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	var m struct {
		Season   int `json:"season_number"`
		Episodes []struct {
			EpisodeNumber int     `json:"episode_number"`
			Name          string  `json:"name"`
			AirDate       string  `json:"air_date"`
			Overview      string  `json:"overview"`
			StillPath     string  `json:"still_path"`
			VoteAverage   float64 `json:"vote_average"`
			Runtime       int     `json:"runtime"`
		} `json:"episodes"`
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		writeErr(w, http.StatusBadGateway, "tmdb decode: "+err.Error())
		return
	}
	resp := struct {
		Season   int           `json:"season"`
		Episodes []episodeItem `json:"episodes"`
	}{Season: m.Season}
	for _, e := range m.Episodes {
		resp.Episodes = append(resp.Episodes, episodeItem{
			Episode:  e.EpisodeNumber,
			Name:     e.Name,
			AirDate:  e.AirDate,
			Still:    imgURL(e.StillPath, "w300"),
			Overview: e.Overview,
			Rating:   e.VoteAverage,
			Runtime:  e.Runtime,
		})
	}
	body, _ := json.Marshal(resp)
	s.cache.putTTL(key, body, 12*time.Hour)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}

// imageClient 专供图片：image.tmdb.org 在国内可以直连(约 1s)，绕开代理比
// 走代理快一个量级。代理通道留给 api.themoviedb.org(直连必然失败)。
var imageClient = &http.Client{
	Timeout: 20 * time.Second,
	Transport: &http.Transport{
		Proxy:               nil, // 显式不走代理
		MaxIdleConns:        64,
		MaxIdleConnsPerHost: 32,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
	},
}

// fetchImage 先直连，失败再用带代理的客户端兜底。
func (s *server) fetchImage(url string) (*http.Response, error) {
	resp, err := imageClient.Get(url)
	if err == nil && resp.StatusCode == http.StatusOK {
		return resp, nil
	}
	if resp != nil {
		resp.Body.Close()
	}
	return s.http.Get(url)
}

// reImagePath 限定图片代理只接受 TMDB 的标准路径，避免把这里当成
// 任意 URL 的转发器（"https://image.tmdb.org/t/p/" + path 里的 path 若
// 带协议或 ../ 就成了开放代理）。
var reImagePath = regexp.MustCompile(`^w\d{2,4}(_and_h\d{2,4})?/[A-Za-z0-9_-]{6,80}\.(jpg|jpeg|png|webp)$`)

// handleImage 代理 TMDB 图片：电视直连 image.tmdb.org 可能不稳，统一走同域。
func (s *server) handleImage(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/tv/img/")
	if path == "" {
		http.NotFound(w, r)
		return
	}
	if !reImagePath.MatchString(path) {
		http.Error(w, "bad image path", http.StatusBadRequest)
		return
	}
	up := "https://image.tmdb.org/t/p/" + path
	resp, err := s.fetchImage(up)
	if err != nil {
		http.Error(w, "upstream: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		http.Error(w, "upstream status "+resp.Status, resp.StatusCode)
		return
	}
	w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "public, max-age=86400")
	_, _ = io.Copy(w, resp.Body)
}

// ---- helpers ----

func envLang(r *http.Request) string {
	if v := r.URL.Query().Get("lang"); v != "" {
		return v
	}
	return "zh-CN"
}

func atoiOr(s string, def int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil || n <= 0 {
		return def
	}
	return n
}

func yearOf(date string) int {
	if len(date) < 4 {
		return 0
	}
	n, _ := strconv.Atoi(date[:4])
	return n
}

func imgURL(path, size string) string {
	if path == "" {
		return ""
	}
	// 用相对路径，客户端拼服务器地址，避免电视直连 TMDB CDN 不稳
	return "/api/tv/img/" + size + path
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// ---- 首页分类行（排行榜）----
//
// 电视上的首页不能只有一条"热门"：国产剧、美剧、动漫、最新/最热这些是用户
// 真正会去翻的入口。这里用 TMDB 的 discover 拼出固定的几个 feed，客户端只
// 传一个 feed 名字，排序和过滤规则全部收在服务端，改规则不用发版。
// realityGenres：10764 真人秀 / 10767 脱口秀 / 10763 新闻。剧集类榜单里
// 这些会把正经剧挤掉。
const realityGenres = "10764,10767,10763"

type feedSpec struct {
	Type   string // movie | tv
	Path   string
	Params url.Values
}

// feedFor 返回某个分类的 TMDB 查询。lang 由调用方补。
func feedFor(name string) (feedSpec, bool) {
	today := time.Now().Format("2006-01-02")
	switch name {
	case "hot_movie":
		return feedSpec{"movie", "/discover/movie", url.Values{
			"sort_by":        {"popularity.desc"},
			"vote_count.gte": {"50"},
		}}, true
	case "new_movie":
		// "最新"：按上映日期倒序，但把还没上映的排掉，否则首页全是预告片
		return feedSpec{"movie", "/discover/movie", url.Values{
			"sort_by":                  {"primary_release_date.desc"},
			"primary_release_date.lte": {today},
			"vote_count.gte":           {"5"},
		}}, true
	case "hot_tv":
		return feedSpec{"tv", "/discover/tv", url.Values{
			"sort_by":        {"popularity.desc"},
			"vote_count.gte": {"20"},
			"without_genres": {realityGenres},
		}}, true
	case "new_tv":
		return feedSpec{"tv", "/discover/tv", url.Values{
			"sort_by":            {"first_air_date.desc"},
			"first_air_date.lte": {today},
			"vote_count.gte":     {"5"},
		}}, true
	case "cn_tv":
		// 国产剧：中国大陆出品。排除综艺/脱口秀/新闻——TMDB 把它们都算 tv，
		// 不排的话"国产剧"第一屏全是《你好，星期六》这种。
		return feedSpec{"tv", "/discover/tv", url.Values{
			"sort_by":             {"popularity.desc"},
			"with_origin_country": {"CN"},
			"without_genres":      {realityGenres},
			"vote_count.gte":      {"5"},
		}}, true
	case "us_tv":
		return feedSpec{"tv", "/discover/tv", url.Values{
			"sort_by":             {"popularity.desc"},
			"with_origin_country": {"US"},
			"without_genres":      {realityGenres},
			"vote_count.gte":      {"20"},
		}}, true
	case "anime":
		// 动漫：动画类型 + 日本出品（含剧场版动画）
		return feedSpec{"tv", "/discover/tv", url.Values{
			"sort_by":             {"popularity.desc"},
			"with_genres":         {"16"},
			"with_origin_country": {"JP"},
			"vote_count.gte":      {"5"},
		}}, true
	case "cn_movie":
		return feedSpec{"movie", "/discover/movie", url.Values{
			"sort_by":             {"popularity.desc"},
			"with_origin_country": {"CN"},
			"vote_count.gte":      {"5"},
		}}, true
	}
	return feedSpec{}, false
}

// fetchFeedPage 抓 TMDB 的某个分类页并转成 searchResponse。
// handleList 和后台预热索引（warmIndex）都走这一条路。
func (s *server) fetchFeedPage(spec feedSpec, lang string, page int) (searchResponse, error) {
	params := url.Values{}
	for k, v := range spec.Params {
		params[k] = v
	}
	params.Set("language", lang)
	params.Set("page", strconv.Itoa(page))
	// 地区相关的分类带上 watch_region，某些国家的分级过滤会更准
	if params.Get("with_origin_country") != "" {
		params.Set("watch_region", "CN")
	}

	raw, err := s.tmdb.get(spec.Path, params)
	if err != nil {
		return searchResponse{}, err
	}

	var parsed struct {
		Page       int `json:"page"`
		TotalPages int `json:"total_pages"`
		Results    []struct {
			ID            int     `json:"id"`
			Name          string  `json:"name"`
			Title         string  `json:"title"`
			OriginalName  string  `json:"original_name"`
			OriginalTitle string  `json:"original_title"`
			PosterPath    string  `json:"poster_path"`
			BackdropPath  string  `json:"backdrop_path"`
			Overview      string  `json:"overview"`
			VoteAverage   float64 `json:"vote_average"`
			FirstAirDate  string  `json:"first_air_date"`
			ReleaseDate   string  `json:"release_date"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return searchResponse{}, fmt.Errorf("tmdb decode: %w", err)
	}

	total := parsed.TotalPages
	if total > 500 {
		total = 500
	}
	out := searchResponse{
		Page:       page,
		TotalPages: total,
		HasMore:    total > 0 && page < total,
	}
	for _, r0 := range parsed.Results {
		title, orig, date := r0.Title, r0.OriginalTitle, r0.ReleaseDate
		if spec.Type == "tv" {
			title, orig, date = r0.Name, r0.OriginalName, r0.FirstAirDate
		}
		if title == "" {
			continue
		}
		out.Items = append(out.Items, searchItem{
			Type:          spec.Type,
			TmdbID:        r0.ID,
			Title:         title,
			OriginalTitle: orig,
			Year:          yearOf(date),
			Poster:        imgURL(r0.PosterPath, "w500"),
			Backdrop:      imgURL(r0.BackdropPath, "w1280"),
			Rating:        r0.VoteAverage,
			Overview:      r0.Overview,
		})
	}
	return out, nil
}

// handleList 给首页分类行提供数据：/api/tv/list?feed=cn_tv&lang=zh-CN
func (s *server) handleList(w http.ResponseWriter, r *http.Request) {
	feed := r.URL.Query().Get("feed")
	spec, ok := feedFor(feed)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unknown feed")
		return
	}
	lang := envLang(r)
	page := 1
	if p := r.URL.Query().Get("page"); p != "" {
		if n, err := strconv.Atoi(p); err == nil && n > 0 {
			page = n
		}
	}
	// TMDB 的 page 上限是 500，越界直接当最后一页
	if page > 500 {
		page = 500
	}
	key := "list:" + lang + ":" + feed + ":" + strconv.Itoa(page)
	if body, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("X-Cache", "hit")
		_, _ = w.Write(body)
		return
	}

	out, err := s.fetchFeedPage(spec, lang, page)
	if err != nil {
		if errors.Is(err, errTMDBNotFound) {
			writeErr(w, http.StatusNotFound, "TMDB 上没有这个分类")
			return
		}
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	// 顺手进拼音索引：用户翻过的分类，之后用首字母就能直接搜到
	s.index.add(out.Items)

	body, _ := json.Marshal(out)
	// 榜单 30 分钟刷一次足够：太短会反复打 TMDB（国内还要过代理）
	s.cache.putTTL(key, body, 30*time.Minute)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(body)
}
