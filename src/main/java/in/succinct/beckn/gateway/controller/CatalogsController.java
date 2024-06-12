package in.succinct.beckn.gateway.controller;

import com.venky.cache.Cache;
import com.venky.core.string.StringUtil;
import com.venky.core.util.MultiException;
import com.venky.core.util.ObjectUtil;
import com.venky.geo.GeoCoordinate;
import com.venky.swf.controller.VirtualModelController;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.Database;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.exceptions.AccessDeniedException;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.collab.db.model.participants.admin.Facility;
import com.venky.swf.plugins.collab.db.model.user.User;
import com.venky.swf.views.View;
import in.succinct.beckn.Address;
import in.succinct.beckn.BecknStrings;
import in.succinct.beckn.Circle;
import in.succinct.beckn.City;
import in.succinct.beckn.Contact;
import in.succinct.beckn.Context;
import in.succinct.beckn.Country;
import in.succinct.beckn.Descriptor;
import in.succinct.beckn.Fulfillment;
import in.succinct.beckn.FulfillmentStop;
import in.succinct.beckn.Item;
import in.succinct.beckn.Location;
import in.succinct.beckn.Locations;
import in.succinct.beckn.Message;
import in.succinct.beckn.Person;
import in.succinct.beckn.Provider;
import in.succinct.beckn.Providers;
import in.succinct.beckn.Request;
import in.succinct.beckn.Scalar;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.db.model.Catalog;
import in.succinct.beckn.gateway.db.model.Company;
import in.succinct.beckn.gateway.util.GWConfig;
import in.succinct.beckn.gateway.util.JsonAttributeSetter;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
import in.succinct.onet.core.api.BecknIdHelper;
import in.succinct.onet.core.api.BecknIdHelper.Entity;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.simple.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CatalogsController extends VirtualModelController<Catalog> {

    public CatalogsController(Path path) {
        super(path);
    }
    private static Address getAddress(String name, com.venky.swf.plugins.collab.db.model.participants.admin.Address facility) {
        Address address = new Address();
        address.setState(facility.getState().getName());
        address.setName(name);
        address.setPinCode(facility.getPinCode().getPinCode());
        address.setCity(facility.getCity().getCode());
        address.setCountry(facility.getCountry().getIsoCode());
        address.setDoor(facility.getAddressLine1());
        address.setBuilding(facility.getAddressLine2());
        address.setStreet(facility.getAddressLine3());
        address.setLocality(facility.getAddressLine4());

        return address;
    }
    @RequireLogin(value = false)
    public View activate(long facilityId) {
        return ingest(facilityId);
    }
    @RequireLogin(value = false)
    public View deactivate(long facilityId) {
        return ingest(facilityId);
    }
    @RequireLogin(value = false)
    public View ingest(long facilityId) {
        if (getReturnIntegrationAdaptor() == null){
            throw new RuntimeException("Only json api is supported.!");
        }
        NetworkAdaptor networkAdaptor = NetworkAdaptorFactory.getInstance().getAdaptor(GWConfig.getNetworkId());
        Providers providers = networkAdaptor.getObjectCreator("").create(Providers.class);
        User user = getSessionUser();
        Facility facility = Database.getTable(Facility.class).get(facilityId);
        if (!facility.isAccessibleBy(user)){
            throw new AccessDeniedException();
        }

        Company company = facility.getCompany().getRawRecord().getAsProxy(Company.class);
        Subscriber subscriber = new Subscriber(){{
           setSubscriberId(company.getSubscriberId());
           setCity(facility.getCity().getCode());
           setCountry(facility.getCountry().getIsoCode());
        }};


        Provider provider = providers.getObjectCreator().create(Provider.class);
        provider.setId(company.getSubscriberId());
        provider.setTag("kyc","tax_id",company.getTaxIdentificationNumber());
        provider.setTag("kyc","registration_id",company.getRegistrationNumber());


        providers.add(provider);
        {
            Descriptor descriptor = provider.getObjectCreator().create(Descriptor.class);
            provider.setDescriptor(descriptor);
            if (!ObjectUtil.isVoid(company.getTaxIdentificationNumber())) {
                descriptor.setCode(company.getTaxIdentificationNumber());
            }
            descriptor.setName(company.getName());
            descriptor.setLongDesc(company.getName());
        }
        {
            Locations locations = provider.getObjectCreator().create(Locations.class);
            Location location = locations.getObjectCreator().create(Location.class);
            location.setGps(new GeoCoordinate(facility));
            location.setCountry(new Country(){{
                setName(facility.getCountry().getName());
                setCode(facility.getCountry().getIsoCode());
            }});
            location.setCity(new City(){{
                setName(facility.getCity().getName());
                setCode(facility.getCity().getCode());
            }});
            location.setId(BecknIdHelper.getBecknId(StringUtil.valueOf(facilityId),subscriber,Entity.provider_location));
            location.setAddress(CatalogsController.getAddress(facility.getName(),facility));
            location.setDescriptor(new Descriptor(){{
                setName(facility.getName());
            }});
            locations.add(location);
            provider.setLocations(locations);
        }

        List<Catalog> catalogs = getIntegrationAdaptor().readRequest(getPath());
        if (catalogs.isEmpty()){
            throw new RuntimeException("No Catalog uploaded");
        }else if (catalogs.size() > 1){
            throw new RuntimeException("Upload one catalog file at a time.");
        }
        BecknStrings locationIds = new BecknStrings(){{
            for (Location location : provider.getLocations()) {
                add(location.getId());
            }
        }};

        MultiException multiException = new MultiException();
        for (Catalog catalog : catalogs){
            InputStream inputStream = catalog.getFile();
            try {
                Workbook book = new XSSFWorkbook(inputStream);
                JSONObject root = provider.getInner();

                for (int i = 0 ; i < book.getNumberOfSheets(); i ++){
                    Sheet sheet = book.getSheetAt(i);
                    importSheet(sheet,root); // In Network format
                }
                provider.setInner(root);
                for (Item item : provider.getItems()) {
                    item.setLocationIds(locationIds);
                }
                Fulfillment fulfillment  = provider.getFulfillments().get("HOME-DELIVERY");
                if (fulfillment != null){
                    if (fulfillment.getContact() == null){
                        fulfillment.setContact(new Contact());
                    }
                    fulfillment.getContact().setEmail(user.getEmail());
                    fulfillment.getContact().setPhone(user.getPhoneNumber());
                    fulfillment.setProviderId(provider.getId());
                    fulfillment.setProviderName(provider.getDescriptor().getName());
                    fulfillment.setTracking(false);
                    fulfillment.setStart(new FulfillmentStop(){{
                        setLocation(provider.getLocations().get(0));
                        Object maxDistance = fulfillment.getTag("APPLICABILITY","MAX_DISTANCE");
                        if (maxDistance != null){
                            getLocation().setCircle(new Circle());
                            getLocation().getCircle().setGps(getLocation().getGps());
                            getLocation().getCircle().setRadius(new Scalar(){{
                                setValue(Database.getJdbcTypeHelper("").getTypeRef(double.class).getTypeConverter().valueOf(maxDistance));
                                setUnit("Km");
                            }});
                        }

                        setContact(fulfillment.getContact());
                        setPerson(new Person(){{
                            setName(user.getLongName());
                        }});
                    }});
                }

                Request request = prepareCatalogSyncRequest(providers, subscriber,networkAdaptor);
                request.setPayload(request.getInner().toString());
                Subscriber gwSubscriber = GWConfig.getSubscriber();

                JSONObject response = new Call<InputStream>().url(gwSubscriber.getSubscriberUrl(),"on_search").headers(new HashMap<>(){{
                    put("content-type",MimeType.APPLICATION_JSON.toString());
                    put("Authorization",request.generateAuthorizationHeader(gwSubscriber.getSubscriberId(),gwSubscriber.getPubKeyId()));
                }}).inputFormat(InputFormat.INPUT_STREAM).method(HttpMethod.POST).input(new ByteArrayInputStream(request.toString().getBytes(StandardCharsets.UTF_8))).getResponseAsJson();

            } catch (IOException e) {
                multiException.add(e);
            }

        }
        if (!multiException.isEmpty()) {
            throw multiException;
        }else {
            return getReturnIntegrationAdaptor().createStatusResponse(getPath(),null,"Catalog update queued!");
        }
    }
    public Request prepareCatalogSyncRequest(Providers providers,  Subscriber subscriber, NetworkAdaptor networkAdaptor){
        Request request = new Request();
        Context context = new Context();
        request.setContext(context);
        request.setMessage(new Message());
        request.getMessage().setCatalog(new in.succinct.beckn.Catalog());
        request.getMessage().getCatalog().setProviders(providers);
        context.setBppId(subscriber.getSubscriberId());
        context.setBppUri(subscriber.getSubscriberUrl());
        context.setTransactionId(UUID.randomUUID().toString());
        context.setMessageId(UUID.randomUUID().toString());
        context.setDomain(subscriber.getDomain());// will go as null.
        context.setCountry(subscriber.getCountry());
        context.setCoreVersion(networkAdaptor.getCoreVersion());
        context.setTimestamp(new Date());
        context.setNetworkId(networkAdaptor.getId());
        context.setCity(subscriber.getCity());
        context.setTtl(60);
        context.setAction("on_search");

        for (in.succinct.beckn.Provider provider : providers){
            if (getPath().action().equals("ingest")) {
                provider.setTag("general_attributes", "catalog.indexer.reset", "Y");
            }else {
                provider.setTag("general_attributes", "catalog.indexer.reset", "N");
                provider.setTag("general_attributes","catalog.indexer.operation",getPath().action());
            }
        }
        return request;

    }

    public void importSheet(Sheet sheet, final JSONObject root){

        Map<String, JsonAttributeSetter> setterMap = new Cache<String, JsonAttributeSetter>(0,0){
            @Override
            protected JsonAttributeSetter getValue(String s) {
                JsonAttributeSetter attributeSetter = new JsonAttributeSetter(s);
                attributeSetter.setJsonAware(root);
                return attributeSetter;
            }
        };
        String sheetName = sheet.getSheetName();

        Row heading = null;
        int rowCount = -1;
        for (Row row : sheet){
            if (heading == null){
                heading = row;
                continue;
            }
            rowCount ++;
            for (int i  = 0  ; i < heading.getLastCellNum() ; i ++){
                Cell headingCell = heading.getCell(i);
                Cell recordCell = row.getCell(i);
                String header = String.format("%s[%d].%s",StringUtil.pluralize(sheetName).toLowerCase(),rowCount,headingCell.getStringCellValue());
                setterMap.get(header).set(recordCell);
            }
        }
    }


    public void setJsonValue(JSONObject o, Cell h, Cell r){
        String hcv = h.getStringCellValue();


    }

}
