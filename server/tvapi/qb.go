package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// qbClient 是 qBittorrent Web API 的最小封装。
//
// 只做电视端需要的三件事：列种子、暂停/恢复、删除（可选连文件）。客户端
// 永远不直连 qB——密码只在服务端，而且电视和 qB 未必在同一个网段。
type qbClient struct {
	base string
	user string
	pass string
	http *http.Client

	mu  sync.Mutex
	sid string
}

func newQBClient(base, user, pass string) *qbClient {
	return &qbClient{
		base: strings.TrimRight(base, "/"),
		user: user,
		pass: pass,
		http: &http.Client{Timeout: 12 * time.Second},
	}
}

// enabled 报告是否配置了 qB。没配时下载页给一句"未配置"而不是 500。
func (q *qbClient) enabled() bool { return q != nil && q.base != "" }

type qbTorrent struct {
	Hash        string  `json:"hash"`
	Name        string  `json:"name"`
	Size        int64   `json:"total_size"`
	Progress    float64 `json:"progress"`
	DlSpeed     int64   `json:"dlspeed"`
	UpSpeed     int64   `json:"upspeed"`
	State       string  `json:"state"`
	Tracker     string  `json:"tracker"`
	Ratio       float64 `json:"ratio"`
	Eta         int64   `json:"eta"`
	SeedingTime int64   `json:"seeding_time"`
	AddedOn     int64   `json:"added_on"`
}

// login 走 /api/v2/auth/login，成功后把 SID 记下来复用。
func (q *qbClient) login(ctx context.Context) error {
	form := url.Values{}
	form.Set("username", q.user)
	form.Set("password", q.pass)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		q.base+"/api/v2/auth/login", strings.NewReader(form.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Referer", q.base)
	resp, err := q.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "Ok") {
		return fmt.Errorf("qb login: HTTP %d %s", resp.StatusCode, string(body))
	}
	for _, c := range resp.Cookies() {
		if c.Name == "SID" {
			q.sid = c.Value
			return nil
		}
	}
	return errors.New("qb login: no SID cookie")
}

// do 发一次带 cookie 的请求；403 时重登一次（qB 重启后 SID 会失效）。
func (q *qbClient) do(ctx context.Context, method, path string, form url.Values) ([]byte, error) {
	body, err := q.doOnce(ctx, method, path, form)
	if errors.Is(err, errQBAuth) {
		if loginErr := q.login(ctx); loginErr != nil {
			return nil, loginErr
		}
		return q.doOnce(ctx, method, path, form)
	}
	return body, err
}

var errQBAuth = errors.New("qb: unauthorized")

func (q *qbClient) doOnce(ctx context.Context, method, path string, form url.Values) ([]byte, error) {
	q.mu.Lock()
	sid := q.sid
	q.mu.Unlock()
	if sid == "" {
		if err := q.login(ctx); err != nil {
			return nil, err
		}
		q.mu.Lock()
		sid = q.sid
		q.mu.Unlock()
	}

	var rdr io.Reader
	if form != nil {
		rdr = strings.NewReader(form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, method, q.base+path, rdr)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Referer", q.base)
	req.AddCookie(&http.Cookie{Name: "SID", Value: sid})
	if form != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	resp, err := q.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusUnauthorized {
		q.mu.Lock()
		q.sid = ""
		q.mu.Unlock()
		return nil, errQBAuth
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("qb %s: HTTP %d %s", path, resp.StatusCode, truncate(string(data), 120))
	}
	return data, nil
}

func (q *qbClient) Torrents(ctx context.Context) ([]qbTorrent, error) {
	body, err := q.do(ctx, http.MethodGet, "/api/v2/torrents/info", nil)
	if err != nil {
		return nil, err
	}
	var out []qbTorrent
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("qb decode: %w", err)
	}
	return out, nil
}

// Action 执行 pause/resume/delete。qB 4.6 用 pause/resume，5.x 改成了
// stop/start，这里按 4.6 走、404 时回退另一套名字。
func (q *qbClient) Action(ctx context.Context, hash, action string, deleteFiles bool) error {
	switch action {
	case "pause", "stop":
		return q.torrentCmd(ctx, hash, "/api/v2/torrents/pause", "/api/v2/torrents/stop")
	case "resume", "start":
		return q.torrentCmd(ctx, hash, "/api/v2/torrents/resume", "/api/v2/torrents/start")
	case "delete":
		form := url.Values{}
		form.Set("hashes", hash)
		if deleteFiles {
			form.Set("deleteFiles", "true")
		} else {
			form.Set("deleteFiles", "false")
		}
		_, err := q.do(ctx, http.MethodPost, "/api/v2/torrents/delete", form)
		return err
	}
	return fmt.Errorf("unknown action %q", action)
}

func (q *qbClient) torrentCmd(ctx context.Context, hash, primary, fallback string) error {
	form := url.Values{}
	form.Set("hashes", hash)
	if _, err := q.do(ctx, http.MethodPost, primary, form); err == nil {
		return nil
	} else if !strings.Contains(err.Error(), "HTTP 404") {
		return err
	}
	_, err := q.do(ctx, http.MethodPost, fallback, form)
	return err
}

// siteFromTracker 把 announce 地址折成站点名：tracker.example.net → example。
// PT 站的种子一般只有一个 tracker，够用；没有 tracker（纯 DHT）显示"PT"。
func siteFromTracker(tracker string) string {
	if tracker == "" {
		return ""
	}
	u, err := url.Parse(tracker)
	if err != nil || u.Hostname() == "" {
		return ""
	}
	host := strings.ToLower(u.Hostname())
	parts := strings.Split(host, ".")
	if len(parts) < 2 {
		return host
	}
	// 去掉 tracker./tr./www. 这类前缀后取主域
	i := len(parts) - 2
	name := parts[i]
	if name == "co" || name == "com" || name == "net" || name == "org" {
		if i > 0 {
			name = parts[i-1]
		}
	}
	return name
}
