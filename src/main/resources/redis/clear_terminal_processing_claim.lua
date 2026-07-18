local value = redis.call('get', KEYS[1])
if not value then
  return 0
end
if string.sub(value, 1, 5) == 'done:' then
  redis.call('del', KEYS[1])
  return 1
end
return 2
