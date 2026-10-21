/** Convert a picker-visible inclusive end date to the API's right-open next day. */
export const nextIsoDate = (value: string): string => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) throw new RangeError('INVALID_ISO_DATE')

  let year = Number(match[1])
  let month = Number(match[2])
  let day = Number(match[3])
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1]) {
    throw new RangeError('INVALID_ISO_DATE')
  }

  if (day < daysInMonth[month - 1]) {
    day += 1
  } else if (month < 12) {
    month += 1
    day = 1
  } else {
    if (year === 9999) throw new RangeError('ISO_DATE_LIMIT')
    year += 1
    month = 1
    day = 1
  }
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}
