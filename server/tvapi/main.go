// tvapi 是 tvpt 电视客户端的元数据与片源聚合服务。
//
// 它做两件事：
//  1. 代理 TMDB（API key 只留在服务端），提供中英文搜索、详情、季集；
//     并把 TMDB id 解析成 imdb id —— 下游 seedstrem 只认 tt 开头的 id。
//  2. 聚合 seedstrem 的片源接口，做集号过滤、补结构化字段（做种数/体积/
//     分辨率），客户端不用再解析 emoji 文案。
//
// 设计原则：不改 seedstrem / Prowlarr / qBittorrent 的任何行为，只做
// 一层薄的、可缓存的适配。
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

type config struct {
	tmdbKey   string
	seedstrem string
	addr      string
	ttl       time.Duration
	// qBittorrent（下载列表/暂停/删除）。没配时下载页显示"未配置"。
	qbURL  string
	qbUser string
	qbPass string
}

func loadConfig() config {
	c := config{
		tmdbKey:   os.Getenv("TMDB_API_KEY"),
		seedstrem: envOr("SEEDSTREM_BASE", "http://seedstrem:8080"),
		addr:      envOr("LISTEN_ADDR", ":8080"),
		ttl:       10 * time.Minute,
		qbURL:     envOr("QB_URL", ""),
		qbUser:    envOr("QB_USERNAME", ""),
		qbPass:    envOr("QB_PASSWORD", ""),
	}
	if c.tmdbKey == "" {
		log.Fatal("tvapi: TMDB_API_KEY is required")
	}
	return c
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

type server struct {
	cfg   config
	cache *cache
	tmdb  *tmdbClient
	http  *http.Client
	qb    *qbClient
	keep  *keepAlive
	// 标题 → 拼音 的内存索引（首字母搜索用，见 pinyin.go）
	index *searchIndex
}

// newHTTPClient 返回一个尊重 HTTP(S)_PROXY 环境变量的客户端。
// 国内直连 api.themoviedb.org 会被 DNS 污染(解析到无关 IP), 必须走宿主
// 机的代理; NO_PROXY 里排除内网服务(seedstrem)。
func newHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			// 关掉 HTTP/2：经 Clash 出去时 h2 连接经常卡死到超时
			// （"http2: timeout"），退到 HTTP/1.1 后同样的请求 1~4s 就回。
			ForceAttemptHTTP2:     false,
			TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
			MaxIdleConns:          32,
			MaxIdleConnsPerHost:   8,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   12 * time.Second,
			ResponseHeaderTimeout: 20 * time.Second,
		},
	}
}

func main() {
	cfg := loadConfig()
	s := &server{
		cfg:   cfg,
		cache: newCache(cfg.ttl),
		tmdb:  newTMDB(cfg.tmdbKey),
		http:  newHTTPClient(40 * time.Second),
		qb:    newQBClient(cfg.qbURL, cfg.qbUser, cfg.qbPass),
		keep:  newKeepAlive(cfg.seedstrem),
		index: newSearchIndex(),
	}
	// "稍后观看"的种子要靠定时 HEAD 续命，否则 5 分钟就被播放策略暂停
	go s.keep.run(context.Background(), 90*time.Second)
	// 后台预热拼音索引：起来之后「kq」这种首字母搜索才立刻有结果
	go s.warmIndex()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/tv/health", s.handleHealth)
	mux.HandleFunc("/api/tv/trending", s.handleTrending)
	mux.HandleFunc("/api/tv/list", s.handleList)
	mux.HandleFunc("/api/tv/search", s.handleSearch)
	mux.HandleFunc("/api/tv/detail", s.handleDetail)
	mux.HandleFunc("/api/tv/season", s.handleSeason)
	mux.HandleFunc("/api/tv/streams", s.handleStreams)
	mux.HandleFunc("/api/tv/img/", s.handleImage)
	mux.HandleFunc("/api/tv/downloads", s.handleDownloads)
	mux.HandleFunc("/api/tv/downloads/action", s.handleDownloadAction)
	mux.HandleFunc("/api/tv/download", s.handleQueueDownload)
	mux.HandleFunc("/api/tv/play", s.handlePlayTorrent)

	srv := &http.Server{
		Addr:              cfg.addr,
		Handler:           withLogging(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("tvapi listening on %s (seedstrem=%s)", cfg.addr, cfg.seedstrem)
	if err := srv.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"service":   "tvapi",
		"seedstrem": s.cfg.seedstrem,
		"index":     s.index.size(),
		"time":      time.Now().Format(time.RFC3339),
	})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		if r.URL.Path != "/api/tv/health" {
			log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
		}
	})
}

// cache 是带 TTL 的简单内存缓存：TMDB 与片源结果都缓存，减少上游压力。
//
// 必须带锁：HTTP handler 是并发的，电视上滑一下首页就会同时打 trending +
// search + 一堆图片请求，map 的并发读写会让 runtime 直接 fatal
// （"concurrent map read and map write"，recover 都拦不住，整个进程退出）。
type cache struct {
	mu   sync.RWMutex
	ttl  time.Duration
	data map[string]cacheEntry
}

type cacheEntry struct {
	body []byte
	exp  time.Time
}

func newCache(ttl time.Duration) *cache {
	return &cache{ttl: ttl, data: make(map[string]cacheEntry)}
}

func (c *cache) get(key string) ([]byte, bool) {
	c.mu.RLock()
	e, ok := c.data[key]
	c.mu.RUnlock()
	if !ok || time.Now().After(e.exp) {
		return nil, false
	}
	return e.body, true
}

func (c *cache) put(key string, body []byte) {
	c.mu.Lock()
	c.data[key] = cacheEntry{body: body, exp: time.Now().Add(c.ttl)}
	c.mu.Unlock()
}

// putTTL 用于长尾数据: 详情/季集几乎不变, 缓存久一点可以把 TMDB 的往返
// (国内必须走代理, 单次 4s 上下) 压成零, 首屏才不会每次都等。
func (c *cache) putTTL(key string, body []byte, ttl time.Duration) {
	c.mu.Lock()
	c.data[key] = cacheEntry{body: body, exp: time.Now().Add(ttl)}
	c.mu.Unlock()
}
