if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('PSETEX', KEYS[1], ARGV[2], 'done:' .. ARGV[1])
  return 1
end
return 0
