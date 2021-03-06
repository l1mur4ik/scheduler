package ru.scheduler.events.service;

import lombok.Synchronized;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.scheduler.events.converter.EventConverter;
import ru.scheduler.events.converter.EventNotificationConverter;
import ru.scheduler.events.exception.EventNotFoundException;
import ru.scheduler.events.model.dto.EventDTO;
import ru.scheduler.events.model.dto.EventNotificationDTO;
import ru.scheduler.events.model.dto.PlaceDTO;
import ru.scheduler.events.model.entity.Event;
import ru.scheduler.events.model.entity.Event.EventId;
import ru.scheduler.events.model.entity.EventInfo;
import ru.scheduler.events.model.entity.EventNotification;
import ru.scheduler.events.model.entity.EventWithUserStatus;
import ru.scheduler.events.model.entity.Place;
import ru.scheduler.events.model.entity.UserEvent;
import ru.scheduler.events.model.entity.UserEventStatus;
import ru.scheduler.events.repository.EventNotificationRepository;
import ru.scheduler.events.repository.EventRepository;
import ru.scheduler.events.repository.UserEventRepository;
import ru.scheduler.scheduling.model.dto.Mail;
import ru.scheduler.scheduling.model.entity.MailTimerTask;
import ru.scheduler.scheduling.service.MailService;
import ru.scheduler.users.model.entity.User;
import ru.scheduler.users.repository.UserRepository;

import javax.mail.MessagingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Timer;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.maxBy;
import static java.util.stream.Collectors.toList;

/**
 * Created by Mikhail Yandimirov on 16.04.2017.
 */

@Service
public class EventService {

    @Autowired
    EventRepository eventRepository;

    @Autowired
    PlaceService placeService;

    @Autowired
    EventInfoService eventInfoService;

    @Autowired
    UserEventRepository userEventRepository;

    @Autowired
    EventNotificationRepository eventNotificationRepository;

    @Autowired
    MailService mailService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventConverter eventConverter;

    @Autowired
    EventNotificationConverter eventNotificationConverter;

    @Value("${client.host.address}")
    String clientAddress;

    @Synchronized
    public Event updateEvent(Event event) {
        EventInfo eventInfo = event.getInfo();
        Place place = eventInfo.getPlace();

        Place foundPlace = place != null ? placeService.findById(place.getId()) : null;

        if (foundPlace == null && place != null) {
            placeService.addPlace(place);
        } else if (foundPlace != null && !foundPlace.equals(place)) {
            throw new IllegalStateException("CHANGE PLACE WITH EXISTING ID IS FORBIDDEN!");
        }

        EventInfo foundEventInfo = eventInfoService.getEventInfo(eventInfo.getId());
        if (!Objects.equals(eventInfo, foundEventInfo)) {
            eventInfo.setId(0);
            eventInfo.setCreatedBy(foundEventInfo.getCreatedBy());
            eventInfoService.addEventInfo(eventInfo);
        }

        event.setCreatedAt(new Date());

        return eventRepository.persist(event);
    }


    public List<User> getUsers(long id) {
        Event event = eventRepository.findLatestVersionById(id).orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
        List<UserEvent> userEvents = userEventRepository.findByEvent(event);
        List<User> users = userEvents.stream()
                .filter(userEvent -> userEvent.getStatus() == UserEventStatus.ACCEPTED)
                .map(UserEvent::getUser)
                .collect(toList());
        return users;
    }

    public List<Event> getUserEvents(long id) {
        User user = userRepository.findOne(id);
        List<UserEvent> userEvents = userEventRepository.findByUser(user);
        return userEvents.stream()
                .map(UserEvent::getEvent)
                .collect(toList());
    }

    public List<Event> getUserEventsByStatus(long id, String status) {
        User user = userRepository.findOne(id);
        List<UserEvent> userEvents = userEventRepository.findByUserAndStatus(user, UserEventStatus.valueOf(status));
        return userEvents.stream()
                .map(UserEvent::getEvent)
                .collect(toList());
    }

    public List<EventWithUserStatus> getAllUserEvents(User user) {
        List<Event> events = getEvents();

        List<EventWithUserStatus> userEvents = events.stream()
                .map(event -> {
                    UserEvent userEvent = userEventRepository.findByEventAndUser(event, user);
                    UserEventStatus status = userEvent == null ? UserEventStatus.UNKNOWN : userEvent.getStatus();

                    return EventWithUserStatus.builder()
                            .compositeId(event.getCompositeId())
                            .status(status)
                            .createdAt(event.getCreatedAt())
                            .startDate(event.getStartDate())
                            .endDate(event.getEndDate())
                            .info(event.getInfo())
                            .build();
                })
                .collect(Collectors.toList());
        return userEvents;
    }

    public List<Event> getEvents() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR, 0);
        calendar.set(Calendar.MINUTE, 0);
        Date date = calendar.getTime();
        return extractLatestVersions(eventRepository.findByStartDateGreaterThanEqual(date));
    }

    public List<Event> getApprovedEventsForCalendar() {
        return extractLatestVersions(eventRepository.findAll());
    }

    private static List<Event> extractLatestVersions(List<Event> events) {
        return events.stream()
                .collect(groupingBy(Event::getId, maxBy(comparing(Event::getVersion))))
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    public Event getEvent(long id, Integer version) {
        if (version == null) {
            return eventRepository.findLatestVersionById(id)
                    .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
        }

        return Optional.ofNullable(eventRepository.findOne(new EventId(id, version)))
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
    }

    public List<Event> getAllVersions(long id) {
        return eventRepository.findAllVersionsById(id);
    }

    public UserEvent getUserEvent(long eventId, User user) {
        Event event = eventRepository.findLatestVersionById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", eventId));
        return userEventRepository.findByEventAndUser(event, user);
    }

    public boolean deleteEvent(long id) {
        Event event = eventRepository.findLatestVersionById(id)
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
        List<UserEvent> userEvents = userEventRepository.findByEvent(event);
        for (UserEvent userEvent : userEvents) {
            List<EventNotification> eventNotifications = eventNotificationRepository
                    .findByEvent(userEvent);
            eventNotificationRepository.delete(eventNotifications);
            String mailText = "Event with name " + userEvent.getEvent().getInfo().getName() + " was removed!";
            Mail mail = Mail.builder()
                    .to(userEvent.getUser().getEmail())
                    .subject("Event was removed")
                    .text(mailText)
                    .build();
            mailService.asyncSend(mail);
            userEventRepository.delete(userEvent);
        }
        List<Event> allVersionsById = eventRepository.findAllVersionsById(id);

        eventRepository.delete(allVersionsById);

        return !eventRepository.findLatestVersionById(id).isPresent();
    }

    public void rejectEvent(long id, User user) {
        Event event = eventRepository.findLatestVersionById(id)
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
        UserEvent userEvent = userEventRepository.findByEventAndUser(event, user);
        userEvent.setStatus(UserEventStatus.REJECTED);
        userEventRepository.save(userEvent);
    }

    public UserEvent subscribeEvent(EventNotificationDTO eventNotificationDTO, User user) {
        Event event = eventRepository.findLatestVersionById(eventNotificationDTO.getId())
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", eventNotificationDTO.getId()));
        UserEvent userEvent = userEventRepository.findByEventAndUser(event, user);
        if (userEvent == null) {
            userEvent = new UserEvent();
        }
        userEvent.setEvent(event);
        userEvent.setUser(user);
        userEvent.setStatus(UserEventStatus.ACCEPTED);
        userEvent.setNotifications(null);
        userEvent = userEventRepository.save(userEvent);
        List<EventNotification> eventNotifications = eventNotificationConverter
                .eventNotificationDtoToEventNotifications(eventNotificationDTO, userEvent);
        for (EventNotification notification : eventNotifications) {
            eventNotificationRepository.save(notification);
            Calendar calendar = Calendar.getInstance();

            calendar.set(Calendar.HOUR_OF_DAY, 2);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            long today = calendar.getTimeInMillis();

            calendar.setTime(notification.getWhen());

            calendar.set(Calendar.HOUR_OF_DAY, 2);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            long notificationDate = calendar.getTimeInMillis();

            if (today == notificationDate) {
                Timer timer = new Timer();
                MailTimerTask task = new MailTimerTask();
                task.setNotificationId(notification.getId());
                task.setEventNotificationRepository(eventNotificationRepository);
                task.setMailService(mailService);
                timer.schedule(task, notification.getWhen());
            }

        }
        //userEvent.setNotifications(eventNotifications);
        return userEvent;
    }

    public boolean unsubscribeEvent(long id, User user) {
        Event event = eventRepository.findLatestVersionById(id)
                .orElseThrow(() -> new EventNotFoundException("Event with id '%s' not found", id));
        UserEvent userEvent = userEventRepository.findByEventAndUser(event, user);
        List<EventNotification> notifications = userEvent.getNotifications();
        for (EventNotification notification : notifications) {
            eventNotificationRepository.delete(notification);
        }
        userEventRepository.delete(userEvent);
        userEvent = userEventRepository.findOne(userEvent.getId());
        return userEvent == null;
    }

    public List<Event> getBirthDaysByUserNot(User user) {
        List<User> users = userRepository.findByEmailNot(user.getEmail());
        List<Event> events = new ArrayList<>();
        for (User u : users) {
            EventInfo eventInfo = EventInfo.builder()
                    .name("День рождения пользователя " + u.getFirstName() + " " + u.getLastName())
                    .build();
            Date start = u.getBirthday();
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            calendar.setTime(start);
            calendar.set(Calendar.YEAR, year);
            for (int i = 0; i < 10; i++) {
                Event event = new Event();
                event.setInfo(eventInfo);
                event.setStartDate(new Date(calendar.getTimeInMillis()));
                event.setEndDate(new Date(calendar.getTimeInMillis() + 86400000));
                events.add(event);
                calendar.add(Calendar.YEAR, 1);
            }
        }
        return events;
    }

    public Event addEvent(Event event) {
        return eventRepository.persist(event);
    }

    public List<Event> addEvents(EventDTO eventDTO) {
        PlaceDTO placeDTO = eventDTO.getPlace();
        Place place = null;
        if (placeDTO != null) {
            place = Place.builder()
                    .id(placeDTO.getId())
                    .name(placeDTO.getName())
                    .lat(placeDTO.getLat())
                    .lon(placeDTO.getLng())
                    .build();

            if (null != placeService.findById(place.getId())) {
                place = placeService.findById(place.getId());
            } else {
                place = placeService.addPlace(place);
            }
        }

        EventInfo eventInfo = EventInfo.builder()
                .name(eventDTO.getName())
                .description(eventDTO.getDescription())
                .createdBy(eventDTO.getCreatedBy())
                .place(place)
                .build();

        eventInfo = eventInfoService.addEventInfo(eventInfo);

        List<Event> events = eventConverter.eventDtoToEvents(eventDTO, eventInfo);

        List<Event> savedEvents = new ArrayList<>();
        for (Event event : events) {
            savedEvents.add(eventRepository.persist(event));
        }
        return savedEvents;
    }

    public void sendNewEventsMailToUsers(List<Long> userIds) {
        userIds.stream()
                .map(userRepository::findOne)
                .map(user -> Mail.builder()
                        .subject("Приглашение на участие в событиях")
                        .to(user.getEmail())
                        .text("Привет, " + user.getFirstName() + "!\n" +
                                "у тебя есть новые приглашеия на участие в собтиях.\n" +
                                "Переходи по ссылке, чтобы узнать подробнее: <a>" + clientAddress + "</a>")
                        .build())
                .forEach(mailService::asyncSend);
    }
}
