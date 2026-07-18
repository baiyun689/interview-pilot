local mode = ARGV[1]

if mode == 'fixed' then
  local capacity = tonumber(ARGV[2])
  local window = tonumber(ARGV[3])
  local count = redis.call('INCR', KEYS[1])
  if count == 1 then
    redis.call('PEXPIRE', KEYS[1], window)
  end
  if count <= capacity then return 1 end
  return 0
end

if mode == 'lease_acquire' then
  local capacity = tonumber(ARGV[2])
  local token = ARGV[3]
  local lease = tonumber(ARGV[4])
  local redis_time = redis.call('TIME')
  local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
  local expires = now + lease
  redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
  if redis.call('ZCARD', KEYS[1]) >= capacity then return 0 end
  redis.call('ZADD', KEYS[1], expires, token)
  local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  redis.call('PEXPIRE', KEYS[1], math.max(1, tonumber(latest[2]) - now))
  return 1
end

if mode == 'lease_release' then
  return redis.call('ZREM', KEYS[1], ARGV[2])
end

return redis.error_reply('unsupported rate limit mode')
