FROM golang:1.26-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY go.mod go.sum ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /tvapi .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata
COPY --from=build /tvapi /tvapi
EXPOSE 8080
ENTRYPOINT ["/tvapi"]
