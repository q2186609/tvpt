package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// downloadItem 是 /api/tv/downloads 的一项。字段名要和客户端
// data/Models.kt 的 DownloadItem 对齐。
type downloadItem struct {
	Hash           string  `json:"hash"`
	Name           string  `json:"name"`
	Size           int64   `json:"size"`
	Progress       float64 `json:"progress"`
	DlSpeed        int64   `json:"dlspeed"`
	UpSpeed        int64   `json:"upspeed"`
	State          string  `json:"state"`
	Site           string  `json:"site"`
	Ratio          float64 `json:"ratio"`
	Eta            int64   `json:"eta"`
	SeedingSeconds int64   `json:"seedingSeconds"`
	AddedOn        int64   `json:"addedOn"`
}

type downloadListResponse struct {
	Items  []downloadItem `json:"items"`
	Online bool           `json:"online"`
	Error  string         `json:"error,omitempty"`
}

func (s *server) handleDownloads(w http.ResponseWriter, r *http.Request) {
	out := downloadListResponse{Online: true}
	if !s.qb.enabled() {
		out.Online = false
		out.Error = "服务端没有配置 qBittorrent"
		writeJSON(w, http.StatusOK, out)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	list, err := s.qb.Torrents(ctx)
	if err != nil {
		out.Online = false
		out.Error = err.Error()
		writeJSON(w, http.StatusOK, out)
		return
	}
	out.Items = make([]downloadItem, 0, len(list))
	for _, t := range list {
		out.Items = append(out.Items, downloadItem{
			Hash:           t.Hash,
			Name:           t.Name,
			Size:           t.Size,
			Progress:       t.Progress,
			DlSpeed:        t.DlSpeed,
			UpSpeed:        t.UpSpeed,
			State:          t.State,
			Site:           siteFromTracker(t.Tracker),
			Ratio:          t.Ratio,
			Eta:            t.Eta,
			SeedingSeconds: t.SeedingTime,
			AddedOn:        t.AddedOn,
		})
	}
	// 正在下载的排前面（按速度降序），其余按加入时间倒序：电视上一眼能看到"还在跑的"。
	sort.SliceStable(out.Items, func(i, j int) bool {
		a, b := out.Items[i], out.Items[j]
		if (a.Progress < 1) != (b.Progress < 1) {
			return a.Progress < 1
		}
		return a.AddedOn > b.AddedOn
	})
	writeJSON(w, http.StatusOK, out)
}

func (s *server) handleDownloadAction(w http.ResponseWriter, r *http.Request) {
	if !s.qb.enabled() {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有配置 qBittorrent")
		return
	}
	q := r.URL.Query()
	hash := strings.TrimSpace(q.Get("hash"))
	action := strings.TrimSpace(q.Get("action"))
	if hash == "" || action == "" {
		writeErr(w, http.StatusBadRequest, "hash 和 action 必填")
		return
	}
	files := q.Get("files") == "true"
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	if err := s.qb.Action(ctx, hash, action, files); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

var reLinkToken = regexp.MustCompile(`/dl/([A-Za-z0-9]+)`)

// handleQueueDownload 实现"稍后观看"：把一条片源丢进下载队列。
//
// 关键点：解析（seedstrem 去 Prowlarr 拉 .torrent 再交给 qB）实测要 20~60 秒，
// 站点慢的时候更久。之前这里是同步等的，20 秒一到就 502/504——用户在电视上
// 看到的就是"加入稍后播放失败"。现在立刻返回 202，解析放到后台重试；种子
// 一落地就挂进 keepAlive 心跳（否则空闲 5 分钟会被播放策略暂停）。
func (s *server) handleQueueDownload(w http.ResponseWriter, r *http.Request) {
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if raw == "" {
		writeErr(w, http.StatusBadRequest, "url 必填")
		return
	}
	target, err := url.Parse(raw)
	if err != nil || target.Host == "" {
		writeErr(w, http.StatusBadRequest, "url 不合法")
		return
	}
	if m, herr := metainfoFromURL(target); herr == nil && m != "" {
		// 已经带 magnet 的片源：直接加，不用等 Prowlarr。
		go s.queueByMagnet(m, target.Query().Get("cid"))
		writeJSON(w, http.StatusAccepted, map[string]any{"ok": true, "queued": true})
		return
	}
	go s.queueByDownload(raw)
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true, "queued": true})
}

// queueByDownload 后台跑"请求 play 链接 → 加种"，失败重试。
func (s *server) queueByDownload(raw string) {
	target, err := url.Parse(raw)
	if err != nil {
		return
	}
	// 片源直链是 seedstrem 用自己的 external_url 拼的（公网域名）。tvapi 在
	// 容器里走域名会绕出去吃代理，直接把 host 换成内网服务名，路径不变。
	if base, berr := url.Parse(s.cfg.seedstrem); berr == nil && base.Host != "" {
		target.Scheme = base.Scheme
		target.Host = base.Host
	}
	// 排队下载不需要"第几集"：seedstrem 会按集号挑文件，挑不到就 404
	// （季包的目录命名经常不带 SxxExx）。去掉集号后它按体积挑最大的视频
	// 文件，加种照样成功——等真正播放时再用带集号的链接重新解析。
	q := target.Query()
	q.Del("series")
	q.Del("s")
	q.Del("e")
	target.RawQuery = q.Encode()
	raw = target.String()
	for attempt := 1; attempt <= 3; attempt++ {
		token, err := s.resolvePlayToken(raw)
		if err == nil {
			if s.keep != nil {
				s.keep.add(token, 6*time.Hour)
			}
			log.Printf("download: queued token=%s (attempt %d)", token, attempt)
			return
		}
		log.Printf("download: queue attempt %d failed: %v", attempt, err)
		time.Sleep(5 * time.Second)
	}
	log.Printf("download: giving up on %s", raw)
}

func (s *server) queueByMagnet(magnet, cid string) {
	hash, _, err := metainfoSplit(magnet)
	if err != nil {
		log.Printf("download: bad magnet: %v", err)
		return
	}
	for attempt := 1; attempt <= 3; attempt++ {
		loc, err := s.playLink(hash, magnet, cid, 0, 0, 0, 90*time.Second)
		if err == nil {
			if m := reLinkToken.FindStringSubmatch(loc); m != nil && s.keep != nil {
				s.keep.add(m[1], 6*time.Hour)
			}
			log.Printf("download: queued magnet %s (attempt %d)", hash, attempt)
			return
		}
		log.Printf("download: magnet attempt %d failed: %v", attempt, err)
		time.Sleep(5 * time.Second)
	}
}

// resolvePlayToken 请求 seedstrem 的 play 链接（不跟随跳转），从 Location 里
// 取出 /dl/<token> 的 token。
func (s *server) resolvePlayToken(raw string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	resp, err := noRedirectClient.Do(req)
	if err != nil {
		return "", err
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	if loc == "" {
		return "", fmt.Errorf("seedstrem HTTP %d", resp.StatusCode)
	}
	m := reLinkToken.FindStringSubmatch(loc)
	if m == nil {
		return "", errors.New("认不出播放 token")
	}
	return m[1], nil
}

var noRedirectClient = &http.Client{
	Timeout: 95 * time.Second,
	CheckRedirect: noRedirect,
}

func noRedirect(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

// handlePlayTorrent 让"下载"页能直接播 qB 里的种子。
//
// 客户端只持有 infohash（qm 列表里没有 seedstrem 的 token），这里用
// magnet:?xt=urn:btih:<hash> 重新走一次 seedstrem 的 play：种子已经在
// qB 里、元数据也齐了，所以这一次是毫秒级的，顺带把"正在播放的文件"
// 优先级拉到最高（边下边播）。
func (s *server) handlePlayTorrent(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	hash := strings.ToLower(strings.TrimSpace(q.Get("hash")))
	if !reInfohash.MatchString(hash) {
		writeErr(w, http.StatusBadRequest, "hash 不合法")
		return
	}
	magnet := "magnet:?xt=urn:btih:" + hash
	if name := strings.TrimSpace(q.Get("name")); name != "" {
		magnet += "&dn=" + url.QueryEscape(name)
	}
	series := q.Get("series") == "1"
	season, _ := strconv.Atoi(q.Get("season"))
	episode, _ := strconv.Atoi(q.Get("episode"))

	loc, err := s.playLink(hash, magnet, q.Get("cid"), boolInt(series), season, episode, 45*time.Second)
	if err != nil && strings.Contains(err.Error(), "no matching file") {
		// 单文件种子（或者文件名里压根没有集号）挑不到"第 N 集"。这种情况下
		// 退一步按体积挑最大的视频文件——总比点了播放什么都不发生强。
		loc, err = s.playLink(hash, magnet, q.Get("cid"), 0, 0, 0, 45*time.Second)
	}
	if err != nil {
		code := http.StatusBadGateway
		msg := err.Error()
		switch {
		case strings.Contains(msg, "metadata"):
			code = http.StatusServiceUnavailable
		case strings.Contains(msg, "no matching file"):
			code = http.StatusNotFound
		}
		writeErr(w, code, msg)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"url": rewriteStreamURL(r, loc)})
}

// playLink 调 seedstrem 的 /stremio/play/<hash>，返回它 302 指向的绝对地址。
func (s *server) playLink(hash, magnet, cid string, series int, season, episode int, timeout time.Duration) (string, error) {
	v := url.Values{}
	v.Set("magnet", magnet)
	if cid != "" {
		v.Set("cid", cid)
	}
	if series == 1 {
		v.Set("series", "1")
		v.Set("s", strconv.Itoa(season))
		v.Set("e", strconv.Itoa(episode))
	}
	up := strings.TrimRight(s.cfg.seedstrem, "/") + "/stremio/play/" + hash + "?" + v.Encode()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, up, nil)
	client := &http.Client{Timeout: timeout, CheckRedirect: noRedirect}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	if resp.StatusCode == http.StatusFound && loc != "" {
		if u, err := url.Parse(loc); err == nil && !u.IsAbs() {
			loc = strings.TrimRight(s.cfg.seedstrem, "/") + loc
		}
		return loc, nil
	}
	return "", fmt.Errorf("%s", strings.TrimSpace(string(body)))
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// metainfoFromURL 从片源 URL 的 magnet 参数里取 magnet（大多数站点会把
// 解析好的 magnet 直接带上）。
func metainfoFromURL(u *url.URL) (string, error) {
	if m := u.Query().Get("magnet"); strings.HasPrefix(m, "magnet:") {
		return m, nil
	}
	return "", nil
}

func metainfoSplit(magnet string) (string, string, error) {
	u, err := url.Parse(magnet)
	if err != nil {
		return "", "", err
	}
	xt := u.Query().Get("xt")
	xt = strings.TrimPrefix(xt, "urn:btih:")
	if !reInfohash.MatchString(strings.ToLower(xt)) {
		return "", "", errors.New("no infohash in magnet")
	}
	return strings.ToLower(xt), u.Query().Get("dn"), nil
}

var reInfohash = regexp.MustCompile(`^[0-9a-f]{40}$`)

// keepAlive 给"稍后观看"的种子续命。
//
// seedstrem 的规则是"停播 idle_pause 后暂停下载"，而排队下载的种子没有播放器
// 连着。这里每隔一会儿对它的 /dl 链接发一个 HEAD，等价于"还有人要看"，让
// 种子保持下载，直到 TTL（默认 6 小时）过期或服务重启。
type keepAlive struct {
	base   string
	http   *http.Client
	mu     sync.Mutex
	tokens map[string]time.Time
}

func newKeepAlive(seedstremBase string) *keepAlive {
	return &keepAlive{
		base:   strings.TrimRight(seedstremBase, "/"),
		http:   &http.Client{Timeout: 12 * time.Second},
		tokens: make(map[string]time.Time),
	}
}

func (k *keepAlive) add(token string, ttl time.Duration) {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.tokens[token] = time.Now().Add(ttl)
}

func (k *keepAlive) run(ctx context.Context, every time.Duration) {
	if every <= 0 {
		every = 90 * time.Second
	}
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			k.tick(ctx)
		}
	}
}

func (k *keepAlive) tick(ctx context.Context) {
	now := time.Now()
	k.mu.Lock()
	live := make([]string, 0, len(k.tokens))
	for token, exp := range k.tokens {
		if now.After(exp) {
			delete(k.tokens, token)
			continue
		}
		live = append(live, token)
	}
	k.mu.Unlock()

	for _, token := range live {
		req, err := http.NewRequestWithContext(ctx, http.MethodHead,
			k.base+"/dl/"+token+"/keep.mkv", nil)
		if err != nil {
			continue
		}
		if resp, err := k.http.Do(req); err == nil {
			resp.Body.Close()
		}
	}
}
