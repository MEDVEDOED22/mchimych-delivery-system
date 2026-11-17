// ==========================================
// 1. АВТОМАТИЧЕСКАЯ ИНИЦИАЛИЗАЦИЯ БАЗЫ ДАННЫХ
// ==========================================
function setupDatabase() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  // 1. Создаем лист Курьеров
  let couriersSheet = ss.getSheetByName('Couriers');
  if (!couriersSheet) {
    couriersSheet = ss.insertSheet('Couriers');
  } else {
    couriersSheet.clear();
  }

  const courierHeaders = ['id', 'login', 'password', 'name', 'phone', 'on_shift'];
  couriersSheet.appendRow(courierHeaders);
  couriersSheet.getRange(1, 1, 1, courierHeaders.length).setFontWeight('bold').setBackground('#e8eaed');

  // Тестовые курьеры
  couriersSheet.appendRow(['c1', 'ilya', '1234', 'Илья Абовян', '+7 (926) 665-31-88', true]);
  couriersSheet.appendRow(['c2', 'ruslan', '1234', 'Руслан К.', '+7 (999) 111-22-33', true]);
  couriersSheet.appendRow(['c3', 'nikita', '1234', 'Никита С.', '+7 (999) 444-55-66', false]);

  // 2. Создаем лист Заказов
  let ordersSheet = ss.getSheetByName('Orders');
  if (!ordersSheet) {
    ordersSheet = ss.insertSheet('Orders');
  } else {
    ordersSheet.clear();
  }

  const orderHeaders = [
    'id',
    'public_number',
    'address',
    'guest_phone',
    'items',
    'comment',
    'payment_status',
    'deadline_iso',
    'deadline_text',
    'courier_id',
    'courier_name',
    'status', // NEW, ASSIGNED, AT_PLACE, GUEST_NO_ANSWER, COMPLETED
    'arrived_at_client',
    'completed_at_client',
    'synced_at_server',
    'was_offline_sync'
  ];
  ordersSheet.appendRow(orderHeaders);
  ordersSheet.getRange(1, 1, 1, orderHeaders.length).setFontWeight('bold').setBackground('#e8eaed');

  // Генерируем дедлайны (+55..65 минут от текущего времени)
  const now = new Date();
  const d1 = new Date(now.getTime() + 62 * 60000);
  const d2 = new Date(now.getTime() + 63 * 60000);
  const d3 = new Date(now.getTime() + 65 * 60000);

  const formatTime = (d) => Utilities.formatDate(d, Session.getScriptTimeZone(), 'HH:mm');

  // Тестовые заказы
  ordersSheet.appendRow([
    'ORD-101',
    '4614 2660 / 9170',
    'Вешняковская улица, Москва, дом 37, подъезд 1, домофон 83, этаж 10, кв. 83',
    '+7 (916) 555-01-01',
    'Филадельфия Нидзя x1, Рамен с курицей x1, Имбирь, Васаби',
    'Домофон работает, подняться на 10 этаж',
    'Оплачен',
    d1.toISOString(),
    formatTime(d1),
    'c1',
    'Илья Абовян',
    'ASSIGNED',
    '', '', '', false
  ]);

  ordersSheet.appendRow([
    'ORD-102',
    '4614 2669 / 9175',
    'Россия, Москва, Самаркандский бульвар, 6 к3, подъезд 1, домофон 22В, этаж 9, кв. 34',
    '+7 (916) 555-02-02',
    'Сет Тануки x1, Лакса с креветками x1, Тайский цыпленок с рисом x1',
    'Позвонить за 5 минут, не работает звонок у двери',
    'Оплачен',
    d2.toISOString(),
    formatTime(d2),
    'c1',
    'Илья Абовян',
    'ASSIGNED',
    '', '', '', false
  ]);

  ordersSheet.appendRow([
    'ORD-103',
    '4614 3422 / 9184',
    'Россия, Москва, Ферганская улица, 15к1, подъезд 1, домофон 6в, этаж 2, кв. 6в',
    '+7 (916) 555-03-03',
    'Калифорния x2, Мисо суп x2, Приборы на 2 персоны',
    'Оставить у двери, если не открывают сразу',
    'Оплачен',
    d3.toISOString(),
    formatTime(d3),
    '',
    '',
    'NEW',
    '', '', '', false
  ]);

  // Удаляем дефолтный пустой "Лист 1", если он есть
  const defaultSheet = ss.getSheetByName('Лист1') || ss.getSheetByName('Sheet1');
  if (defaultSheet && ss.getSheets().length > 1) {
    ss.deleteSheet(defaultSheet);
  }
}

// ==========================================
// 2. ОБРАБОТКА GET-ЗАПРОСОВ
// ==========================================
function doGet(e) {
  const action = (e && e.parameter && e.parameter.action) ? e.parameter.action : 'get_all';

  try {
    if (action === 'get_all') {
      return jsonResponse({
        ok: true,
        server_time: new Date().toISOString(),
        couriers: getSheetData('Couriers'),
        orders: getSheetData('Orders')
      });
    }

    if (action === 'get_courier_orders') {
      const courierId = e.parameter.courier_id;
      const allOrders = getSheetData('Orders');
      const filtered = allOrders.filter(o => String(o.courier_id) === String(courierId));
      return jsonResponse({
        ok: true,
        server_time: new Date().toISOString(),
        orders: filtered
      });
    }

    return jsonResponse({ ok: false, error: 'Unknown GET action' });
  } catch (err) {
    return jsonResponse({ ok: false, error: err.toString() });
  }
}

// ==========================================
// 3. ОБРАБОТКА POST-ЗАПРОСОВ (АВТОРИЗАЦИЯ, СИНХРОНИЗАЦИЯ, ДИСПЕТЧЕРСКАЯ)
// ==========================================
function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000); // Защита от гонки потоков при пакетной синхронизации

  try {
    const body = JSON.parse(e.postData.contents);
    const action = body.action;

    // 1. Авторизация курьера
    if (action === 'login') {
      const couriers = getSheetData('Couriers');
      const user = couriers.find(
        c => String(c.login).toLowerCase() === String(body.login).trim().toLowerCase() &&
             String(c.password) === String(body.password).trim()
      );
      if (!user) {
        return jsonResponse({ ok: false, error: 'Неверный логин или пароль' });
      }
      return jsonResponse({
        ok: true,
        courier: {
          id: user.id,
          login: user.login,
          name: user.name,
          phone: user.phone,
          on_shift: Boolean(user.on_shift)
        }
      });
    }

    // 2. Переключение статуса смены
    if (action === 'toggle_shift') {
      const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Couriers');
      const data = sheet.getDataRange().getValues();
      for (let i = 1; i < data.length; i++) {
        if (String(data[i][0]) === String(body.courier_id)) {
          sheet.getRange(i + 1, 6).setValue(Boolean(body.on_shift));
          return jsonResponse({ ok: true, on_shift: Boolean(body.on_shift) });
        }
      }
      return jsonResponse({ ok: false, error: 'Courier not found' });
    }

    // 3. Создание нового заказа с сайта диспетчера
    if (action === 'create_order') {
      const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Orders');
      const id = 'ORD-' + Math.floor(1000 + Math.random() * 9000);
      const pubNum = body.public_number || ('4614 ' + Math.floor(1000 + Math.random() * 8999) + ' / ' + Math.floor(1000 + Math.random() * 8999));

      const minutes = Number(body.delivery_minutes || 60);
      const deadlineDate = new Date(new Date().getTime() + minutes * 60000);
      const deadlineText = Utilities.formatDate(deadlineDate, Session.getScriptTimeZone(), 'HH:mm');

      const courierId = body.courier_id || '';
      const courierName = body.courier_name || '';
      const initialStatus = courierId ? 'ASSIGNED' : 'NEW';

      const newRow = [
        id,
        pubNum,
        body.address || 'Москва, Рязанский проспект, 45, подъезд 2, этаж 5, кв. 42',
        body.guest_phone || '+7 (999) 000-11-22',
        body.items || 'Ролл Филадельфия x1, Рамен x1, Соевый соус',
        body.comment || 'Домофон работает',
        body.payment_status || 'Оплачен',
        deadlineDate.toISOString(),
        deadlineText,
        courierId,
        courierName,
        initialStatus,
        '', '', '', false
      ];
      sheet.appendRow(newRow);
      return jsonResponse({ ok: true, order_id: id });
    }

    // 4. Назначение курьера с сайта диспетчера
    if (action === 'assign_courier') {
      const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Orders');
      const data = sheet.getDataRange().getValues();
      for (let i = 1; i < data.length; i++) {
        if (String(data[i][0]) === String(body.order_id)) {
          sheet.getRange(i + 1, 10).setValue(body.courier_id);
          sheet.getRange(i + 1, 11).setValue(body.courier_name);
          sheet.getRange(i + 1, 12).setValue(body.courier_id ? 'ASSIGNED' : 'NEW');
          return jsonResponse({ ok: true });
        }
      }
      return jsonResponse({ ok: false, error: 'Order not found' });
    }

    // 5. Одиночное или пакетное обновление статусов из Android (Offline-First Queue)
    if (action === 'sync_events') {
      // body.events = [{ order_id, new_status, client_timestamp, was_offline }]
      const events = Array.isArray(body.events) ? body.events : [body];
      const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Orders');
      const data = sheet.getDataRange().getValues();
      const serverNow = new Date().toISOString();
      let processedCount = 0;

      events.forEach(ev => {
        for (let i = 1; i < data.length; i++) {
          if (String(data[i][0]) === String(ev.order_id)) {
            const rowIdx = i + 1;
            const newStatus = ev.new_status;
            const clientTs = ev.client_timestamp || serverNow;
            const wasOffline = Boolean(ev.was_offline);

            // Обновляем статус (колонка 12)
            sheet.getRange(rowIdx, 12).setValue(newStatus);

            if (newStatus === 'AT_PLACE') {
              sheet.getRange(rowIdx, 13).setValue(clientTs);
            }
            if (newStatus === 'COMPLETED') {
              sheet.getRange(rowIdx, 14).setValue(clientTs);
            }

            // Всегда фиксируем серверное время синхронизации и флаг офлайна
            sheet.getRange(rowIdx, 15).setValue(serverNow);
            if (wasOffline) {
              sheet.getRange(rowIdx, 16).setValue(true);
            }
            processedCount++;
            break;
          }
        }
      });

      return jsonResponse({
        ok: true,
        processed: processedCount,
        synced_at_server: serverNow
      });
    }

    return jsonResponse({ ok: false, error: 'Unknown POST action' });
  } catch (err) {
    return jsonResponse({ ok: false, error: err.toString() });
  } finally {
    lock.releaseLock();
  }
}

// ==========================================
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
// ==========================================
function getSheetData(sheetName) {
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(sheetName);
  if (!sheet) return [];
  const rows = sheet.getDataRange().getValues();
  if (rows.length < 2) return [];
  const headers = rows[0];
  const result = [];

  for (let i = 1; i < rows.length; i++) {
    const obj = {};
    for (let j = 0; j < headers.length; j++) {
      obj[headers[j]] = rows[i][j];
    }
    result.push(obj);
  }
  return result;
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}